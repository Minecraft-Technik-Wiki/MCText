package de.piegames.mctext;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.zip.ZipException;

import com.flowpowered.nbt.ByteArrayTag;
import com.flowpowered.nbt.ByteTag;
import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.flowpowered.nbt.DoubleTag;
import com.flowpowered.nbt.EndTag;
import com.flowpowered.nbt.FloatTag;
import com.flowpowered.nbt.IntArrayTag;
import com.flowpowered.nbt.IntTag;
import com.flowpowered.nbt.ListTag;
import com.flowpowered.nbt.LongArrayTag;
import com.flowpowered.nbt.LongTag;
import com.flowpowered.nbt.ShortArrayTag;
import com.flowpowered.nbt.ShortTag;
import com.flowpowered.nbt.StringTag;
import com.flowpowered.nbt.Tag;
import com.flowpowered.nbt.TagType;
import com.flowpowered.nbt.stream.NBTInputStream;
import com.flowpowered.nbt.stream.NBTOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public class Converter {

	public static final PathMatcher nbt = FileSystems.getDefault().getPathMatcher("glob:**.{dat,dat_old,dat_new,nbt}");
	public static final PathMatcher anvil = FileSystems.getDefault().getPathMatcher("glob:**.{mca,mcr}");

	protected Options options;

	public final Gson gson;

	public Converter() {
		this(Options.DEFAULT_OPTIONS);
	}

	public Converter(Options options) {
		this.options = Objects.requireNonNull(options);

		GsonBuilder builder = new GsonBuilder();
		builder.setLenient();
		if (options.prettyPrinting)
			builder.setPrettyPrinting();
		builder.registerTypeAdapter(CompoundTag.class, TAG_ADAPTER);
		builder.registerTypeAdapter(RegionFile.class, REGION_ADAPTER);
		gson = builder.create();
	}

	public final TypeAdapter<CompoundTag> TAG_ADAPTER = new TypeAdapter<CompoundTag>() {

		@Override
		public void write(JsonWriter out, CompoundTag value) throws IOException {
			Converter.this.write(out, value, "", false);
		}

		@Override
		public CompoundTag read(JsonReader in) throws IOException {
			return (CompoundTag) Converter.this.read(in, "", TagType.TAG_COMPOUND);
		}
	};

	public final TypeAdapter<RegionFile> REGION_ADAPTER = new TypeAdapter<RegionFile>() {

		@Override
		public void write(JsonWriter out, RegionFile value) throws IOException {
			out.beginObject();

			for (int i = 0; i < 1024; i++) {
				int chunkPos = value.locations2.get(i) >>> 8;
				int chunkLength = value.locations2.get(i) & 0xFF;
				int timestamp = value.timestamps2.get(i);

				if (value.chunks[i] != null) {
					out.name(Integer.toString(chunkPos));
					out.beginObject();

					out.name("index");
					out.value(i);
					out.name("timestamp");
					out.value(timestamp);

					ByteBuffer data = value.chunks[i];
					int realChunkLength = data.getInt() - 1;
					int compression = data.get();
					out.name("compression");
					out.value(compression);

					NBTInputStream nbtIn = new NBTInputStream(
							new ByteArrayInputStream(data.array(), 5, realChunkLength), compression);
					out.name("chunk");
					Converter.this.write(out, nbtIn.readTag(), "chunk", false);
					nbtIn.close();

					if (options.keepUnusedData) {
						byte[] unusedData = new byte[(chunkLength << 12) - realChunkLength - 5];
						System.arraycopy(data.array(), realChunkLength + 5, unusedData, 0, unusedData.length);
						out.name("unused");
						out.value(Base64.getEncoder().encodeToString(unusedData));
					}

					out.endObject();
				}
			}

			if (value.unused != null && options.keepUnusedData)
				for (Entry<Integer, ByteBuffer> e : value.unused.entrySet()) {
					out.name(e.getKey().toString());
					out.value(Base64.getEncoder().encodeToString(e.getValue().array()));
				}
			out.endObject();
		}

		@Override
		public RegionFile read(JsonReader in) throws IOException {
			ByteBuffer locations, timestamps;
			IntBuffer locations2, timestamps2;
			ByteBuffer[] chunks = new ByteBuffer[1024];

			locations = ByteBuffer.allocate(4096);
			timestamps = ByteBuffer.allocate(4096);
			locations2 = locations.asIntBuffer();
			timestamps2 = timestamps.asIntBuffer();

			Map<Integer, ByteBuffer> unused = new HashMap<>();

			in.beginObject();

			while (in.peek() != JsonToken.END_OBJECT) {
				String name = in.nextName();
				int chunkPos = Integer.parseInt(name);
				if (in.peek() == JsonToken.BEGIN_OBJECT) { // Actual chunk data
					in.beginObject();

					if (!in.nextName().equals("index"))
						throw new JsonParseException("index must be first element in object");
					int i = in.nextInt();

					if (!in.nextName().equals("timestamp"))
						throw new JsonParseException("timestamp must be the third element in object");
					int timestamp = in.nextInt();

					if (!in.nextName().equals("compression"))
						throw new JsonParseException("compression must be the fourth element in object");
					byte compression = (byte) in.nextInt();

					if (!in.nextName().equals("chunk"))
						throw new JsonParseException("chunk must be the fifth element in object");

					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					NBTOutputStream s = new NBTOutputStream(
							new BufferedOutputStream(baos = new ByteArrayOutputStream()), compression);
					s.writeTag(Converter.this.read(in, "", TagType.TAG_COMPOUND));
					s.flush();
					s.close();

					byte[] chunk = baos.toByteArray();
					int chunkLength = (int) Math.ceil((chunk.length + 6) / 4096d);
					chunks[i] = ByteBuffer.allocate(chunkLength << 12);
					chunks[i].putInt(chunk.length + 1);
					chunks[i].put(compression);
					chunks[i].put(chunk);

					if (in.peek() != JsonToken.END_OBJECT) {
						// Read unused data
						if (options.keepUnusedData) {
							in.nextName();
							byte[] unused2 = Base64.getDecoder().decode(in.nextString());
							chunks[i].put(unused2);
						} else { // Discard unused data
							in.nextName();
							in.nextString();
						}
					}

					chunks[i].flip();

					locations2.put(i, chunkPos << 8 | (chunkLength & 0xFF));
					timestamps2.put(i, timestamp);

					in.endObject();
				} else if (options.keepUnusedData) { // Unused data
					ByteBuffer value = ByteBuffer.wrap(Base64.getDecoder().decode(in.nextString()));
					unused.put(chunkPos, value);
				}
			}
			in.endObject();
			return new RegionFile(locations, timestamps, chunks, unused);
		}
	};

	void write(JsonWriter out, Tag<?> nbt, String name, boolean writeName) throws IOException {
		if (nbt.getType() != TagType.TAG_LIST && name != null && writeName)
			out.name(encode(name, nbt.getType()));

		switch (nbt.getType()) {
		case TAG_BYTE:
			out.value((byte) nbt.getValue());
			break;
		case TAG_DOUBLE:
			out.value((double) nbt.getValue());
			break;
		case TAG_FLOAT:
			out.value((float) nbt.getValue());
			break;
		case TAG_INT:
			out.value((int) nbt.getValue());
			break;
		case TAG_LONG:
			out.value((long) nbt.getValue());
			break;
		case TAG_SHORT:
			out.value((short) nbt.getValue());
			break;
		case TAG_STRING:
			out.value((String) nbt.getValue());
			break;
		case TAG_BYTE_ARRAY:
			out.value(Base64.getEncoder().encodeToString((byte[]) nbt.getValue()));
			break;
		case TAG_INT_ARRAY: {
			int[] data = (int[]) nbt.getValue();
			ByteBuffer byteBuffer = ByteBuffer.allocate(data.length * 4);
			IntBuffer intBuffer = byteBuffer.asIntBuffer();
			intBuffer.put(data);
			out.value(Base64.getEncoder().encodeToString(byteBuffer.array()));
			break;
		}
		case TAG_SHORT_ARRAY: {
			short[] data = (short[]) nbt.getValue();
			ByteBuffer byteBuffer = ByteBuffer.allocate(data.length * 2);
			ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
			shortBuffer.put(data);
			out.value(Base64.getEncoder().encodeToString(byteBuffer.array()));
			break;
		}
		case TAG_LONG_ARRAY: {
			long[] data = (long[]) nbt.getValue();
			ByteBuffer byteBuffer = ByteBuffer.allocate(data.length * 8);
			LongBuffer longBuffer = byteBuffer.asLongBuffer();
			longBuffer.put(data);
			out.value(Base64.getEncoder().encodeToString(byteBuffer.array()));
			break;
		}
		case TAG_COMPOUND: {
			CompoundMap map = ((CompoundTag) nbt).getValue();
			out.beginObject();
			for (Entry<String, Tag<?>> tags : map.entrySet()) {
				String key = tags.getKey();
				Tag<?> value = tags.getValue();
				write(out, value, key, true);
			}
			out.endObject();
			break;
		}
		case TAG_LIST: {
			@SuppressWarnings("unchecked")
			ListTag<Tag<?>> list = (ListTag<Tag<?>>) nbt;
			out.name(encode(encode(name, TagType.getByTagClass(list.getElementType())), TagType.TAG_LIST));
			out.beginArray();
			for (Tag<?> tag : list.getValue())
				write(out, tag, "", false);
			out.endArray();
			break;
		}
		case TAG_END:
			break;
		default:
			throw new Error();
		}
	}

	/**
	 * @param name
	 *            the real name of the key. If type==LIST, the first two chars of
	 *            the name indicate the type of the list
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	Tag<?> read(JsonReader in, String name, TagType type) throws IOException {
		switch (type) {
		case TAG_BYTE:
			return new ByteTag(name, (byte) in.nextInt());
		case TAG_DOUBLE:
			return new DoubleTag(name, in.nextDouble());
		case TAG_FLOAT:
			return new FloatTag(name, (float) in.nextDouble());
		case TAG_INT:
			return new IntTag(name, in.nextInt());
		case TAG_LONG:
			return new LongTag(name, in.nextLong());
		case TAG_SHORT:
			return new ShortTag(name, (short) in.nextInt());
		case TAG_STRING:
			return new StringTag(name, in.nextString());
		case TAG_BYTE_ARRAY:
			return new ByteArrayTag(name, Base64.getDecoder().decode(in.nextString()));
		case TAG_INT_ARRAY: {
			IntBuffer intBuffer = ByteBuffer.wrap(Base64.getDecoder().decode(in.nextString())).asIntBuffer();
			int[] array = new int[intBuffer.remaining()];
			intBuffer.get(array);
			return new IntArrayTag(name, array);
		}
		case TAG_SHORT_ARRAY: {
			ShortBuffer shortBuffer = ByteBuffer.wrap(Base64.getDecoder().decode(in.nextString())).asShortBuffer();
			short[] array = new short[shortBuffer.remaining()];
			shortBuffer.get(array);
			return new ShortArrayTag(name, shortBuffer.array());
		}
		case TAG_LONG_ARRAY: {
			LongBuffer longBuffer = ByteBuffer.wrap(Base64.getDecoder().decode(in.nextString())).asLongBuffer();
			long[] array = new long[longBuffer.remaining()];
			longBuffer.get(array);
			return new LongArrayTag(name, longBuffer.array());
		}
		case TAG_COMPOUND: {
			CompoundMap map = new CompoundMap();
			CompoundTag compound = new CompoundTag(name, map);
			in.beginObject();
			while (in.peek() != JsonToken.END_OBJECT) {
				String key = in.nextName();
				TagType t = decode(key);
				if (t == TagType.TAG_END)
					break;
				key = key.substring(2);
				map.put(read(in, key, t));
			}
			in.endObject();
			return compound;
		}
		case TAG_LIST: {
			TagType listType = decode(name);
			name = name.substring(2);
			List<Tag> tags = new LinkedList<Tag>();
			in.beginArray();
			while (in.peek() != JsonToken.END_ARRAY)
				tags.add(read(in, "", listType));
			in.endArray();
			return new ListTag<Tag>(name, (Class<Tag>) listType.getTagClass(), tags);
		}
		case TAG_END:
			return new EndTag();
		default:
			throw new Error();
		}
	}

	static TagType decode(String key) {
		return TagType.getById(Integer.parseInt(key.substring(0, 2), 16));
	}

	static String encode(String key, TagType type) {
		return String.format("%02x", type.getId()) + key;
	}

	public void backupFile(Path original, Path backup) throws IOException {
		System.out.println(original + " -> " + backup);
		if (nbt.matches(original))
			backupNBT(original, backup);
		else if (anvil.matches(original)) {
			backupAnvil(original, backup);
		} else {
			System.out.println("\tis not an NBT file and will be copied");
			if (options.overwriteExisting)
				Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
			else
				Files.copy(original, backup);
		}
	}

	public void backupNBT(Path original, Path backup) throws IOException {
		System.out.println("\tis NBT file and will be converted");
		if (options.safeMode || (Files.exists(backup) && !options.overwriteExisting))
			return;
		try (NBTInputStream s = new NBTInputStream(Files.newInputStream(original));
				Writer writer = Files.newBufferedWriter(backup)) {
			writer.write(gson.toJson(s.readTag()));
		} catch (ZipException e) {
			// File might be uncompressed
			try (NBTInputStream s = new NBTInputStream(Files.newInputStream(original), NBTInputStream.NO_COMPRESSION);
					Writer writer = Files.newBufferedWriter(backup)) {
				writer.write(gson.toJson(s.readTag()));
			}
		}
	}

	public void backupAnvil(Path original, Path backup) throws IOException {
		System.out.println("\tis an anvil file and will be converted");
		if (options.safeMode || (Files.exists(backup) && !options.overwriteExisting))
			return;
		try (Writer writer = Files.newBufferedWriter(backup)) {
			writer.write(gson.toJson(new RegionFile(original)));
		}
	}

	public void backupWorld(Path original, Path backup) throws IOException {
		// Throw an exception if destination already exists

		// if (!Files.exists(destination))
		System.out.println("Creating " + backup);
		Files.createDirectories(backup);
		// if (!Files.isDirectory(destination))
		// throw new FileAlreadyExistsException(destination.toString());

		Files.walkFileTree(original, new FileVisitor<Path>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				dir = backup.resolve(original.relativize(dir));
				System.out.println("Creating " + dir);
				Files.createDirectories(dir);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				backupFile(file, backup.resolve(original.relativize(file)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				return FileVisitResult.TERMINATE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public void restoreFile(Path backup, Path restore) throws IOException {
		System.out.println(restore + " <- " + backup);
		if (nbt.matches(backup))
			restoreNBT(backup, restore);
		else if (anvil.matches(backup)) {
			restoreAnvil(backup, restore);
		} else {
			System.out.println("\tis not an NBT file and will be copied");
			if (!options.safeMode) {
				if (options.overwriteExisting)
					Files.copy(backup, restore, StandardCopyOption.REPLACE_EXISTING);
				else
					Files.copy(backup, restore);
			}
		}
	}

	public void restoreNBT(Path backup, Path restore) throws IOException {
		System.out.println("\tis NBT file and will be converted");
		if (options.safeMode || (Files.exists(restore) && !options.overwriteExisting))
			return;
		try (NBTOutputStream s = new NBTOutputStream(Files.newOutputStream(restore))) {
			s.writeTag(gson.fromJson(new String(Files.readAllBytes(backup)), CompoundTag.class));
		}
	}

	public void restoreAnvil(Path backup, Path restore) throws IOException {
		System.out.println("\tis an anvil file and will be converted");
		if (options.safeMode || (Files.exists(restore) && !options.overwriteExisting))
			return;

		gson.fromJson(new String(Files.readAllBytes(backup)), RegionFile.class).write(restore);
	}

	public void restoreWorld(Path backup, Path restore) throws IOException {
		// Throw an exception if destination already exists

		// if (!Files.exists(destination))
		System.out.println("Creating " + restore);
		Files.createDirectories(restore);
		// if (!Files.isDirectory(destination))
		// throw new FileAlreadyExistsException(destination.toString());

		Files.walkFileTree(backup, new FileVisitor<Path>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				dir = restore.resolve(backup.relativize(dir));
				System.out.println("Creating " + dir);
				Files.createDirectories(dir);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				restoreFile(file, restore.resolve(backup.relativize(file)));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				return FileVisitResult.TERMINATE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static void main(String[] args) throws IOException {
		Converter converter = new Converter(new Options(false, false, false, false));
		if (Boolean.valueOf("true"))
			converter.backupWorld(Paths.get("/home/piegames/.minecraft/saves/Multi 5 - Kopie 1 (copy)"),
					Paths.get("/home/piegames/.minecraft/saves/Multi 5 - Kopie 1 (copy) NBT!!!"));
		else
			converter.restoreWorld(Paths.get("/home/piegames/.minecraft/saves/Multi 5 - Kopie 1 (copy) NBT!!!"),
					Paths.get("/home/piegames/.minecraft/saves/Multi 5 - Kopie 1 (copy) JSON!!!"));

		// RandomAccessFile raf = new
		// RandomAccessFile("/home/piegames/.minecraft/saves/Redstone/region/r.1.0.mca",
		// "r");
		//
		// // byte 1-3: chunk position in 4kb sectors from file start
		// long chunkPos = (raf.read() << 16 | raf.read() << 8 | raf.read());
		// if (chunkPos > 0) {
		// raf.seek(chunkPos * 4096);
		// // Coords in world
		// int chunkLength = (raf.read() << 24 | raf.read() << 16 | raf.read() << 8 |
		// raf.read()) - 1;
		// int compression = raf.read();// skip compression
		// {// NBT section here
		// byte[] buf = new byte[chunkLength];
		// raf.read(buf);
		// NBTInputStream nbtIn = new NBTInputStream(new InflaterInputStream(new
		// ByteArrayInputStream(buf)),
		// false);
		// System.out.println(nbtIn.readTag());
		// nbtIn.close();
		// }
		// } // byte 4: chunk length in sectors
		// raf.skipBytes(1);// skip it
	}
}