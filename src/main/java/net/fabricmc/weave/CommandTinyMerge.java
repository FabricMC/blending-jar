/*
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.weave;

import com.google.common.base.Charsets;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

// TODO: Remap descriptors on fields and methods.
public class CommandTinyMerge extends Command {
	public enum TinyEntryType {
		ROOT,
		CLASS,
		FIELD,
		METHOD;

		private static Map<String, TinyEntryType> BY_NAME = new HashMap<>();

		public static TinyEntryType byName(String s) {
			return BY_NAME.get(s);
		}

		static {
			for (TinyEntryType type : values()) {
				BY_NAME.put(type.name(), type);
			}
		}
	}

	public static class TinyEntry {
		public final TinyEntryType type;
		public final String header;
		// Map<index, name>
		public final Map<String, String> names = new HashMap<>();
		// Table<index, name, instance>
		public final Table<String, String, TinyEntry> children = HashBasedTable.create();
		private TinyEntry parent;

		public TinyEntry(TinyEntryType type, String header) {
			this.type = type;
			this.header = header;
		}

		public TinyEntry getParent() {
			return parent;
		}

		public void addChild(TinyEntry entry, String nameSuffix) {
			entry.parent = this;

			for (Map.Entry<String, String> e : entry.names.entrySet()) {
				String key = e.getKey();
				String value = e.getValue() + nameSuffix;

				if (children.contains(key, value)) {
					throw new RuntimeException("Duplicate TinyEntry: (" + key + ", " + value + ")!");
				}

				children.put(key, value, entry);
			}
		}
	}

	public static class TinyFile {
		public final String[] indexList;
		public final TinyEntry root = new TinyEntry(TinyEntryType.ROOT, "");
		public final int typeCount;

		public TinyFile(File f) throws IOException {
			BufferedReader reader = Files.newBufferedReader(f.toPath(), Charsets.UTF_8);
			String[] header = reader.readLine().trim().split("\t");
			if (header.length < 3 || !header[0].trim().equals("v1")) {
				throw new RuntimeException("Invalid header!");
			}

			typeCount = header.length - 1;
			indexList = new String[typeCount];
			for (int i = 0; i < typeCount; i++) {
				indexList[i] = header[i + 1].trim();
			}

			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.length() == 0 || line.charAt(0) == '#') {
					continue;
				}

				String[] parts = line.split("\t");
				for (int i = 0; i < parts.length; i++) {
					parts[i] = parts[i].trim();
				}

				StringBuilder prefix = new StringBuilder();
				prefix.append(parts[0]);
				for (int i = 1; i < parts.length - typeCount; i++) {
					prefix.append('\t');
					prefix.append(parts[i]);
				}

				String[] path = parts[1].split("\\$");
				TinyEntry parent = root;
				TinyEntryType type = TinyEntryType.byName(parts[0]);

				for (int i = 0; i < (type == TinyEntryType.CLASS ? path.length - 1 : path.length); i++) {
					TinyEntry nextParent = parent.children.get(indexList[0], path[i]);
					if (nextParent == null) {
						nextParent = new TinyEntry(TinyEntryType.CLASS, "CLASS");
						nextParent.names.put(indexList[0], path[i]);
						parent.addChild(nextParent, "");
					}
					parent = nextParent;
				}

				TinyEntry entry;
				if (type == TinyEntryType.CLASS && parent.children.contains(indexList[0], path[path.length - 1])) {
					entry = parent.children.get(indexList[0], path[path.length - 1]);
				} else {
					entry = new TinyEntry(type, prefix.toString());
				}

				String[] names = new String[typeCount];
				for (int i = 0; i < typeCount; i++) {
					names[i] = parts[parts.length - typeCount + i];
					String[] splitly = names[i].split("\\$");
					entry.names.put(indexList[i], splitly[splitly.length - 1]);
				}

				switch (type) {
					case CLASS:
						parent.addChild(entry, "");
						break;
					case FIELD:
					case METHOD:
						parent.addChild(entry, parts[2]);
						break;
				}
			}

			reader.close();
		}
/*
		public String match(String[] entries, String key) {
			if (indexMap.containsKey(key)) {
				return entries[indexMap.get(key)];
			} else {
				return null;
			}
		}
*/
	}

	private List<String> mappingBlankFillOrder = new ArrayList<>();

	public CommandTinyMerge() {
		super("tinyMerge");
	}

	@Override
	public String getHelpString() {
		return "<input-a> <input-b> <output> [mappingBlankFillOrder...]";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 4;
	}

	private String fixMatch(TinyEntry a, TinyEntry b, String matchA, String index) {
		if (a == null || matchA == null) {
			return matchA;
		}

		if (b == null) {
			b = a;
		}

		if (a.type == TinyEntryType.CLASS) {
			a = a.getParent();
			b = b.getParent();
			while (a.type == TinyEntryType.CLASS) {
				String m = a.names.get(index);
				if (m == null) {
					m = b.names.get(index);
					if (m == null) {
						for (String s : mappingBlankFillOrder) {
							m = a.names.get(s);
							if (m != null) {
								break;
							} else {
								m = b.names.get(s);
								if (m != null) {
									break;
								}
							}
						}

						if (m == null) {
							throw new RuntimeException("TODO");
						}
					}
				}

				matchA = m + "$" + matchA;
				a = a.getParent();
				b = b.getParent();
			}
		}

		return matchA;
	}

	private String getMatch(TinyEntry a, TinyEntry b, String index) {
		String matchA = a != null ? a.names.get(index) : null;
		String matchB = b != null ? b.names.get(index) : null;

		matchA = fixMatch(a, b, matchA, index);
		matchB = fixMatch(b, a, matchB, index);

		if (matchA != null) {
			if (matchB != null && !matchA.equals(matchB)) {
				throw new RuntimeException("No match: " + index + " " + matchA + " " + matchB);
			}

			return matchA;
		} else {
			return matchB;
		}
	}

	private String getEntry(TinyEntry a, TinyEntry b, List<String> totalIndexList) {
		if (a != null && b != null && !(a.header.equals(b.header))) {
			throw new RuntimeException("Header mismatch: " + a.header + " " + b.header);
		}

		String header = a != null ? a.header : b.header;
		StringBuilder entry = new StringBuilder();
		entry.append(header);

		for (String index : totalIndexList) {
			entry.append('\t');

			String match = getMatch(a, b, index);
			if (match == null) {
				for (String s : mappingBlankFillOrder) {
					match = getMatch(a, b, s);
					if (match != null) {
						break;
					}
				}

				if (match == null) {
					throw new RuntimeException("TODO");
				}
			}

			entry.append(match);
		}

		entry.append('\n');
		return entry.toString();
	}

	public void write(TinyEntry inputA, TinyEntry inputB, String index, String c, BufferedWriter writer, List<String> totalIndexList, int indent) throws IOException {
		TinyEntry classA = inputA != null ? inputA.children.get(index, c) : null;
		TinyEntry classB = inputB != null ? inputB.children.get(index, c) : null;
/*
		for (int i = 0; i <= indent; i++)
			System.out.print("-");
		System.out.println(" " + c + " " + (classA != null ? "Y" : "N") + " " + (classB != null ? "Y" : "N"));
*/
		if ((classA == null || classA.names.size() == 0) && (classB == null || classB.names.size() == 0)) {
			System.out.println("Warning: empty!");
			return;
		}

		writer.write(getEntry(classA, classB, totalIndexList));

		Set<String> subKeys = new TreeSet<>();
		if (classA != null) subKeys.addAll(classA.children.row(index).keySet());
		if (classB != null) subKeys.addAll(classB.children.row(index).keySet());
		for (String cc : subKeys) {
			write(classA, classB, index, cc, writer, totalIndexList, indent + 1);
		}
	}

	public void run(File inputAf, File inputBf, File outputf, String... mappingBlankFillOrderValues) throws IOException {
		for (String s : mappingBlankFillOrderValues) {
			if (!this.mappingBlankFillOrder.contains(s)) {
				this.mappingBlankFillOrder.add(s);
			}
		}

		System.out.println("Reading " + inputAf.getName());
		TinyFile inputA = new TinyFile(inputAf);

		System.out.println("Reading " + inputBf.getName());
		TinyFile inputB = new TinyFile(inputBf);

		System.out.println("Processing...");
		BufferedWriter writer = Files.newBufferedWriter(outputf.toPath(), Charsets.UTF_8);
		if (!inputA.indexList[0].equals(inputB.indexList[0])) {
			throw new RuntimeException("TODO");
		}

		// Set<String> matchedIndexes = Sets.intersection(inputA.indexMap.keySet(), inputB.indexMap.keySet());
		Set<String> matchedIndexes = Collections.singleton(inputA.indexList[0]);
		List<String> totalIndexList = Lists.newArrayList(inputA.indexList);
		for (String s : inputB.indexList) {
			if (!totalIndexList.contains(s)) {
				totalIndexList.add(s);
			}
		}
		int totalIndexCount = totalIndexList.size();

		// emit header
		StringBuilder header = new StringBuilder();
		header.append("v1");
		for (String s : totalIndexList) {
			header.append('\t');
			header.append(s);
		}
		writer.write(header.append('\n').toString());

		// collect classes
		String index = inputA.indexList[0];
		Set<String> classKeys = new TreeSet<>();
		classKeys.addAll(inputA.root.children.row(index).keySet());
		classKeys.addAll(inputB.root.children.row(index).keySet());

		// emit entries
		for (String c : classKeys) {
			write(inputA.root, inputB.root, index, c, writer, totalIndexList, 0);
		}

		writer.close();
		System.out.println("Done!");
	}

	@Override
	public void run(String[] args) throws Exception {
		File inputAf = new File(args[0]);
		File inputBf = new File(args[1]);
		File outputf = new File(args[2]);

		String[] mbforder = new String[args.length - 3];
		for (int i = 3; i < args.length; i++) {
			mbforder[i - 3] = args[i];
		}

		run(inputAf, inputBf, outputf, mbforder);
	}
}
