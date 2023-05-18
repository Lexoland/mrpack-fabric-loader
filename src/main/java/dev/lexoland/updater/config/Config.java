package dev.lexoland.updater.config;

import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.lexoland.updater.Updater;

public class Config {

	private static final Gson GSON = new Gson();

	public static String projectId;
	public static String authToken;
	public static String gameVersion;
	public static List<String> alwaysOverrideFiles = Collections.singletonList(".jar");

	public static void load() {
		if (!Updater.USER_CONFIG_FILE.exists())
			return;

		try (FileReader reader = new FileReader(Updater.USER_CONFIG_FILE)) {
			JsonObject config = GSON.fromJson(reader, JsonObject.class);
			projectId = config.has("projectId") ? config.get("projectId").getAsString() : null;
			authToken = config.has("authToken") ? config.get("authToken").getAsString() : null;
			gameVersion = config.has("gameVersion") ? config.get("gameVersion").getAsString() : null;
			alwaysOverrideFiles = GSON.fromJson(
					config.has("alwaysOverrideFiles") ? config.get("alwaysOverrideFiles") : null,
					new TypeToken<ArrayList<String>>() {}.getType()
			);
		} catch (Exception e) {
			throw new RuntimeException("Failed to load updater config", e);
		}
	}

	public static void save() {
		if (!Updater.UPDATER_DIR.exists())
			Updater.UPDATER_DIR.mkdirs();

		JsonObject config = new JsonObject();
		config.addProperty("projectId", projectId);
		config.addProperty("authToken", authToken);
		config.addProperty("gameVersion", gameVersion);
		config.add("alwaysOverrideFiles", GSON.toJsonTree(alwaysOverrideFiles));

		try(FileWriter writer = new FileWriter(Updater.USER_CONFIG_FILE)) {
			GSON.toJson(config, writer);
		} catch (Exception e) {
			throw new RuntimeException("Failed to save updater config", e);
		}
	}

	public static boolean shouldAskForProject() {
		return projectId == null || projectId.isEmpty()
				|| authToken == null || authToken.isEmpty()
				|| gameVersion == null || gameVersion.isEmpty();
	}

}
