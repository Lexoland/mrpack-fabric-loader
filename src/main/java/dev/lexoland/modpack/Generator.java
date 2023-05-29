package dev.lexoland.modpack;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

public class Generator {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final String MODRINTH_API = "https://api.modrinth.com/v2/version/";
	private static final String PACK_FILE = "./modpackgen/modpack/modrinth.index.json";
	private static final String OVERRIDE_MODS_DIR = "./modpackgen/modpack/overrides/mods/";

	private static final ModrinthCallbackHandler MODRINTH_CALLBACK_HANDLER = new ModrinthCallbackHandler();

	private static final JsonObject MODPACK_JSON = new JsonObject();

	private static final List<JsonObject> MODS = new ArrayList<>();
	private static final Map<Request, String> REQUEST_NAME_MAP = new HashMap<>();

	private static int requestCount;
	private static AtomicInteger callbackCount = new AtomicInteger();

	public static void main(String[] args) throws IOException, URISyntaxException {
		File file = new File("./modpackgen/modlist.txt");
		if (!file.exists()) {
			System.out.println("Modlist file not found!");
			return;
		}

		MODPACK_JSON.addProperty("formatVersion", 1);
		MODPACK_JSON.addProperty("game", "minecraft");
		MODPACK_JSON.addProperty("versionId", "1.0.0");
		MODPACK_JSON.addProperty("name", "CreateWithWorldgen");
		MODPACK_JSON.addProperty("summary", "A modpack for Create and Worldgen");

		List<Request> requests = createModrinthRequests(file);
		requestCount = requests.size() + REQUEST_NAME_MAP.size();
		System.out.println("Found " + requestCount + " mods");

		OkHttpClient client = new OkHttpClient();

		for (Request request : requests)
			client.newCall(request).enqueue(MODRINTH_CALLBACK_HANDLER);

		for (Map.Entry<Request, String> entry : REQUEST_NAME_MAP.entrySet()) {
			Request request = entry.getKey();
			String name = entry.getValue();
			client.newCall(request).enqueue(new CurseforgeCallbackHandler(name));
		}

		System.out.println("Getting mod info...");
	}

	private static void finish() throws IOException {
		MODPACK_JSON.add("files", GSON.toJsonTree(MODS));
		System.out.println("Finished getting mod info");
		System.out.println("Writing to file...");
		File file = new File(PACK_FILE);
		if(!file.exists()) {
			System.out.println("File not found, creating...");
			file.getParentFile().mkdirs();
			if(!file.createNewFile()) {
				System.out.println("Failed to create file!");
				return;
			}
		}

		JsonObject dependencies = new JsonObject();
		dependencies.add("minecraft", new JsonPrimitive("1.19.2"));
		dependencies.add("fabric-loader", new JsonPrimitive("0.14.21"));
		MODPACK_JSON.add("dependencies", dependencies);

		try(FileWriter writer = new FileWriter(file)) {
			GSON.toJson(MODPACK_JSON, writer);
		}
		System.out.println("Done!");
		System.exit(0);
	}


	private static List<Request> createModrinthRequests(File file) throws IOException {
		List<Request> urls = new ArrayList<>();

		try(BufferedReader in = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.startsWith("#") || line.isEmpty())
					continue;

				if(line.startsWith("!")) {
					REQUEST_NAME_MAP.put(new Request.Builder()
							.url(line.substring(line.indexOf(";") + 1))
							.get()
							.build(),
							line.substring(1, line.indexOf(";"))
					);
					continue;
				}

				urls.add(new Request.Builder()
						.url(MODRINTH_API + line)
						.get()
						.build()
				);
			}
		}
		return urls;
	}

	private static class ModrinthCallbackHandler implements Callback {

		@Override
		public void onFailure(@NotNull Call call, @NotNull IOException e) {
			System.out.println("Failed to get mod info: " + e.getMessage());
			if(callbackCount.incrementAndGet() == requestCount) {
				try {
					Generator.finish();
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
		}

		@Override
		public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
			if(!response.isSuccessful()) {
				System.out.println("Failed to get mod info for " + call.request().url());
				System.out.println("Response code: " + response.code());
				if(callbackCount.incrementAndGet() == requestCount)
					Generator.finish();
				return;
			}

			JsonObject json = GSON.fromJson(response.body().charStream(), JsonObject.class)
					.getAsJsonArray("files")
					.get(0)
					.getAsJsonObject();

			json.remove("primary");
			String fileName = json.remove("filename").getAsString();

			long fileSize = json.get("size").getAsLong();
			json.remove("size");
			json.addProperty("fileSize", fileSize);

			json.addProperty("path", "mods/" + fileName);

			String url = json.remove("url").getAsString();

			JsonArray downloads = new JsonArray();
			downloads.add(new JsonPrimitive(url));
			json.add("downloads", downloads);

			MODS.add(json);
			if(callbackCount.incrementAndGet() == requestCount)
				Generator.finish();
		}
	}

	private static class CurseforgeCallbackHandler implements Callback {

		private final String name;

		public CurseforgeCallbackHandler(String name) {
			this.name = name;
		}

		@Override
		public void onFailure(@NotNull Call call, @NotNull IOException e) {
			System.out.println("Failed to get mod info: " + e.getMessage());
			if(callbackCount.incrementAndGet() == requestCount) {
				try {
					Generator.finish();
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
			call.cancel();
		}

		@Override
		public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
			if(!response.isSuccessful()) {
				System.out.println("Failed to get mod info for " + call.request().url());
				System.out.println("Response code: " + response.code());
				if(callbackCount.incrementAndGet() == requestCount)
					Generator.finish();
				return;
			}

			File file = new File(OVERRIDE_MODS_DIR + name + ".jar");

			if(!file.exists()) {
				file.getParentFile().mkdirs();
				if(!file.createNewFile()) {
					System.out.println("Failed to create file!");
					return;
				}
			}

			try(InputStream in = response.body().byteStream()) {
				try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()))) {
					byte[] buffer = new byte[1024];
					int read;
					while ((read = in.read(buffer, 0, 1024)) >= 0)
						out.write(buffer, 0, read);
					System.out.println("Downloaded " + name);
				}
			}

			if(callbackCount.incrementAndGet() == requestCount)
				Generator.finish();
			response.close();
			call.cancel();
		}
	}
}
