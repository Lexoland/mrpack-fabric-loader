package dev.lexoland.updater;

import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.lexoland.updater.config.Config;
import dev.lexoland.updater.config.ProjectSelectionWindow;
import dev.lexoland.updater.rendering.UpdateRenderer;
import dev.lexoland.updater.rendering.UpdateWindow;
import dev.lexoland.updater.rendering.stages.CreateBackupStage;
import dev.lexoland.updater.rendering.stages.DownloadPackFilesStage;
import dev.lexoland.updater.rendering.stages.DownloadPackMetaStage;
import dev.lexoland.updater.rendering.stages.ExtractOverridesStage;
import dev.lexoland.updater.rendering.stages.FinishUpStage;
import dev.lexoland.updater.rendering.stages.RestoreBackupStage;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import javax.swing.*;

public class Updater {

	public static final String MR_ENDPOINT = "https://api.modrinth.com/v2";
	private static final Gson GSON = new Gson();

	public static final File UPDATER_DIR = new File("updater");
	public static final File USER_CONFIG_FILE = new File(UPDATER_DIR, "config.json");
	public static final File PACK_FILE = new File(UPDATER_DIR, "pack.mrpack");
	public static final File INSTALLATION_INFO_FILE = new File(UPDATER_DIR, "installation-info.json");
	public static final File BACKUP_FILE = new File(UPDATER_DIR, "backup.zip");

	private static final ImmutableList<String> DOWNLOAD_DOMAIN_WHITELIST = ImmutableList.of(
			"cdn.modrinth.com",
			"github.com",
			"raw.githubusercontent.com",
			"gitlab.com"
	);

	private static Updater instance;


	private final OkHttpClient client;

	private final String projectId;
	private final String gameVersion;
	private final Collection<String> alwaysOverrideFiles;
	private final EnvType environment;

	private String entryName;
	private int totalEntries;
	private int currentEntry;

	private long downloadSize;
	private long downloaded;

	private long downloadSpeed;
	private long downloadSpeedTimestamp;
	private long downloadSpeedCounter;

	private boolean startGame = true;
	private boolean backupCreated = false;
	private boolean needsCleanup = false;
	private final InstallationInfo previousInstallationInfo = new InstallationInfo();
	private final InstallationInfo newInstallationInfo = new InstallationInfo();

	private Updater(
			String projectId,
			String gameVersion,
			String authToken,
			Collection<String> alwaysOverrideFiles,
			EnvType environment
	) {
		this.projectId = projectId;
		this.gameVersion = gameVersion;
		this.alwaysOverrideFiles = alwaysOverrideFiles;
		this.environment = environment;

		this.client = createHttpClient(authToken);
	}

	public void checkForUpdates() {
		loadPreviousInstallationInfo();
		String currentVersionNumber = previousInstallationInfo.versionNumber;

		Log.info(LogCategory.UPDATER, "Current pack version: %s", currentVersionNumber == null ? "None" : currentVersionNumber);

		Request request = new Request.Builder()
				.url(MR_ENDPOINT + "/project/" + projectId + "/version?loaders=[\"fabric\"]&game_versions=[\"" + gameVersion + "\"]")
				.get()
				.build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				throw new IOException("Unexpected code " + response);
			}

			JsonArray versions = GSON.fromJson(response.body().charStream(), JsonArray.class);

			if (versions.size() == 0) {
				Log.warn(LogCategory.UPDATER, "No versions found");
				return;
			}

			JsonObject version = versions.get(0).getAsJsonObject();
			String newVersionNumber = version.get("version_number").getAsString();

			if (newVersionNumber.equals(currentVersionNumber)) {
				Log.info(LogCategory.UPDATER, "Pack is up to date");
				return;
			}
			Log.info(LogCategory.UPDATER, "New version found: %s", newVersionNumber);

			UpdateWindow.open();

			needsCleanup = true;
			createBackup();

			// get primary file
			UpdateRenderer.setStage(new DownloadPackMetaStage(this));

			JsonObject file = StreamSupport.stream(version.getAsJsonArray("files").spliterator(), false)
					.map(JsonElement::getAsJsonObject)
					.filter(f -> f.get("primary").getAsBoolean())
					.findAny()
					.orElseThrow(() -> new RuntimeException("No primary file found"));

			String fileName = file.get("filename").getAsString();
			String downloadUrl = file.get("url").getAsString();

			downloadFile(fileName, HttpUrl.get(downloadUrl), PACK_FILE, true);
			downloadPackFiles();
			extractOverrides();

			UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Removed Pack Files...", 0, 3));
			deleteRemovedPackFiles();

			UpdateRenderer.setStage(new FinishUpStage(this, "Saving Installation Info...", 1, 3));
			newInstallationInfo.versionNumber = newVersionNumber;
			saveNewInstallationInfo();
		} catch (Exception e) {
			Log.error(LogCategory.UPDATER, "Failed to check/update pack.", e);
			showUpdateFailedOptionPane(e);

			if (backupCreated)
				restoreBackup();
		} finally {
			if(needsCleanup) {
				UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Cache...", 2, 3));
				cleanUp();
				UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Cache...", 3, 3));
			}
			UpdateWindow.close();
		}
	}


	private void downloadFile(String fileName, HttpUrl downloadUrl, File destination, boolean meta) throws IOException {
		Log.info(LogCategory.UPDATER, "Downloading '%s'...", fileName);
		entryName = fileName;
		downloaded = 0;

		File parent = destination.getParentFile();
		if (parent != null && !parent.exists())
			parent.mkdirs();

		Request request = new Request.Builder()
				.url(downloadUrl)
				.get()
				.build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful())
				throw new IOException("Unexpected response code " + response);

			ResponseBody body = response.body();
			downloadSize = body.contentLength();

			InputStream in = body.byteStream();
			FileOutputStream out = new FileOutputStream(destination);

			byte[] buffer = new byte[1024];
			int read;

			while ((read = in.read(buffer, 0, 1024)) != -1) {
				out.write(buffer, 0, read);
				downloaded += read;
				downloadSpeedCounter += read;

				long now = System.currentTimeMillis();
				if(now - downloadSpeedTimestamp > 1000) {
					downloadSpeedTimestamp = now;
					downloadSpeed = downloadSpeedCounter;
					downloadSpeedCounter = 0;
				}
			}

			out.close();
		}
	}

	private void downloadPackFiles() throws IOException {
		Log.info(LogCategory.UPDATER, "Downloading pack files...");

		UpdateRenderer.setStage(new DownloadPackFilesStage(this));

		JsonObject index = getPackIndex();
		JsonArray files = index.getAsJsonArray("files");

		totalEntries = files.size();
		currentEntry = 0;

		for (JsonElement element : files) {
			JsonObject file = element.getAsJsonObject();

			JsonObject env = file.getAsJsonObject("env");
			if (env != null) {
				String clientSupport = env.get("client").getAsString();
				String serverSupport = env.get("server").getAsString();

				if (environment == EnvType.CLIENT && clientSupport.equalsIgnoreCase("unsupported"))
					continue;
				if (environment == EnvType.SERVER && serverSupport.equalsIgnoreCase("unsupported"))
					continue;
			}

			String path = file.get("path").getAsString();
			if (path.contains("..") || path.matches("^([A-Z]:/|[A-Z]:\\\\|/|\\\\)")) {
				Log.warn(LogCategory.UPDATER, "Skipping file '%s' because it has an invalid path", path);
				continue;
			}
			File destination = new File(path);

			JsonArray downloads = file.getAsJsonArray("downloads");
			if (downloads.size() == 0)
				continue;
			HttpUrl downloadUrl = HttpUrl.get(downloads.get(0).getAsString());
			if (!DOWNLOAD_DOMAIN_WHITELIST.contains(downloadUrl.host())) {
				Log.warn(LogCategory.UPDATER, "Skipping file '%s' because it is hosted on an invalid domain (%s)", path, downloadUrl.host());
				continue;
			}

			String hash = file.getAsJsonObject("hashes").get("sha512").getAsString();

			if (destination.exists()) {
				String localHash = Files.asByteSource(destination).hash(Hashing.sha512()).toString();
				if (localHash.equals(hash)) {
					markAsInstalled(destination);
					currentEntry++;
					continue;
				}
			}

			markAsInstalled(destination);
			downloadFile(destination.getName(), downloadUrl, destination, false);
			currentEntry++;
		}
	}

	private void deleteRemovedPackFiles() {
		for (int i = 0; i < previousInstallationInfo.files.size(); i++) {
			String installedFile = previousInstallationInfo.files.get(i);
			if (newInstallationInfo.files.contains(installedFile))
				continue;

			File file = new File(installedFile);
			if (!file.exists())
				continue;

			Log.info(LogCategory.UPDATER, "Deleting removed pack file '%s'", installedFile);
			file.delete();
		}
	}

	private void extractOverrides() throws IOException {
		Log.info(LogCategory.UPDATER, "Extracting overrides...");
		UpdateRenderer.setStage(new ExtractOverridesStage(this));


		try (ZipFile zipFile = new ZipFile(PACK_FILE)) {
			totalEntries = zipFile.size();
			currentEntry = 0;

			Enumeration<? extends ZipEntry> entries = zipFile.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory()) {
					currentEntry++;
					continue;
				}

				if (entryName.startsWith("overrides/"))
					extractOverrideEntry(zipFile, entry, "overrides/");
				else if (entryName.startsWith("client-overrides/") && environment == EnvType.CLIENT)
					extractOverrideEntry(zipFile, entry, "client-overrides/");
				else if (entryName.startsWith("server-overrides/") && environment == EnvType.SERVER)
					extractOverrideEntry(zipFile, entry, "server-overrides/");

				currentEntry++;
			}
		}
	}

	private void extractOverrideEntry(ZipFile file, ZipEntry entry, String prefixToRemove) throws IOException {
		String destinationPath = entry.getName().substring(prefixToRemove.length());

		entryName = destinationPath;

		File destination = new File(destinationPath);
		String name = destination.getName();

		markAsInstalled(destination);

		if (destination.exists() && alwaysOverrideFiles.stream().noneMatch(name::endsWith))
			return;

		InputStream in = file.getInputStream(entry);

		File parent = destination.getParentFile();
		if (parent != null && !parent.exists())
			parent.mkdirs();

		Files.asByteSink(destination).writeFrom(in);
	}

	@SuppressWarnings("IOStreamConstructor")
	private void createBackup() throws IOException {
		Log.info(LogCategory.UPDATER, "Creating backup...");
		UpdateRenderer.setStage(new CreateBackupStage(this));

		totalEntries = previousInstallationInfo.files.size() + 1;
		currentEntry = 0;

		if (!UPDATER_DIR.exists())
			UPDATER_DIR.mkdirs();

		try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(BACKUP_FILE))) {

			if (INSTALLATION_INFO_FILE.exists()) {
				entryName = INSTALLATION_INFO_FILE.getPath();

				ZipEntry installationInfoEntry = new ZipEntry(INSTALLATION_INFO_FILE.getPath());
				out.putNextEntry(installationInfoEntry);
				Files.asByteSource(INSTALLATION_INFO_FILE).copyTo(out);
				out.closeEntry();

				currentEntry++;
			}

			for (int i = 0; i < previousInstallationInfo.files.size(); i++) {
				String installedFile = previousInstallationInfo.files.get(i);

				entryName = installedFile;

				File file = new File(installedFile);
				if (!file.exists())
					continue;

				ZipEntry entry = new ZipEntry(file.getPath());
				out.putNextEntry(entry);
				Files.asByteSource(file).copyTo(out);
				out.closeEntry();

				currentEntry++;
			}
		}
		backupCreated = true;
	}

	private void restoreBackup() {
		Log.info(LogCategory.UPDATER, "Restoring backup...");

		// delete all new files
		UpdateRenderer.setStage(new RestoreBackupStage(this, false));

		List<File> toDelete = newInstallationInfo.files.stream()
				.filter(file -> !previousInstallationInfo.files.contains(file))
				.map(File::new)
				.filter(File::exists)
				.collect(Collectors.toList());

		totalEntries = toDelete.size();
		currentEntry = 0;

		for (File file : toDelete) {
			file.delete();
			currentEntry++;
		}


		// restore backup file
		UpdateRenderer.setStage(new RestoreBackupStage(this, false));

		try (ZipFile zipFile = new ZipFile(BACKUP_FILE)) {

			totalEntries = zipFile.size();
			currentEntry = 0;

			Enumeration<? extends ZipEntry> entries = zipFile.entries();


			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory()) {
					currentEntry++;
					continue;
				}

				File destination = new File(entryName);

				File parent = destination.getParentFile();
				if (parent != null && !parent.exists())
					parent.mkdirs();

				Files.asByteSink(destination).writeFrom(zipFile.getInputStream(entry));

				currentEntry++;
			}
		} catch (IOException e) {
			Log.error(LogCategory.UPDATER, "Failed to restore backup.", e);
		}
	}

	private void cleanUp() {
		Log.info(LogCategory.UPDATER, "Cleaning up...");

		if (BACKUP_FILE.exists())
			BACKUP_FILE.delete();

		if (PACK_FILE.exists())
			PACK_FILE.delete();
	}

	private void markAsInstalled(File file) {
		newInstallationInfo.files.add(file.getPath());
	}

	private void loadPreviousInstallationInfo() {
		if (!INSTALLATION_INFO_FILE.exists())
			return;

		try (FileReader reader = new FileReader(INSTALLATION_INFO_FILE)) {
			JsonObject installationInfoObject = GSON.fromJson(reader, JsonObject.class);
			previousInstallationInfo.versionNumber = installationInfoObject.get("version").getAsString();
			previousInstallationInfo.files = GSON.fromJson(installationInfoObject.get("files"), new TypeToken<ArrayList<String>>() {}.getType());
		} catch (IOException e) {
			throw new RuntimeException("Failed to load installation info.", e);
		}
	}

	private void saveNewInstallationInfo() {
		try (FileWriter writer = new FileWriter(INSTALLATION_INFO_FILE)) {
			JsonObject installationInfoObject = new JsonObject();
			installationInfoObject.addProperty("version", newInstallationInfo.versionNumber);
			installationInfoObject.add("files", GSON.toJsonTree(newInstallationInfo.files));
			GSON.toJson(installationInfoObject, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save installation info.", e);
		}
	}

	private JsonObject getPackIndex() throws IOException {
		try (ZipFile zipFile = new ZipFile(PACK_FILE)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();

			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().equals("modrinth.index.json") || entry.isDirectory())
					continue;

				InputStreamReader reader = new InputStreamReader(zipFile.getInputStream(entry));

				return GSON.fromJson(reader, JsonObject.class);
			}
		}
		throw new RuntimeException("No modrinth.index.json found in pack.");
	}

	private void showUpdateFailedOptionPane(Exception e) {
		Toolkit.getDefaultToolkit().beep();

		String exceptionMessage = e.getMessage() == null ? "" : e.getMessage();
		String message = backupCreated
				? "\n\nA backup of your previous installation will be restored.\nDo you want to start the game anyway after the\nrestore has been completed?"
				: "\n\nDo you want to start the game anyway?";

		int selection = JOptionPane.showConfirmDialog(
				UpdateWindow.getInstance(),
				exceptionMessage + message,
				"Update Failed",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.ERROR_MESSAGE
		);

		if (selection == JOptionPane.NO_OPTION)
			startGame = false;
	}

	public int getTotalEntries() {
		return totalEntries;
	}

	public int getCurrentEntry() {
		return currentEntry;
	}

	public long getDownloaded() {
		return downloaded;
	}

	public long getDownloadSize() {
		return downloadSize;
	}

	public long getDownloadSpeed() {
		return downloadSpeed;
	}

	public String getEntryName() {
		return entryName;
	}

	private static class InstallationInfo {
		private String versionNumber = null;
		private List<String> files = new ArrayList<>();
	}

	public static void launch(EnvType envType, String gameVersion, Runnable onFinish) {
		Log.finishBuiltinConfig();

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
			Log.error(LogCategory.UPDATER, "Failed to set system look and feel", e);
		}

		Config.load();

		if (Config.shouldAskForProject()) {
			ProjectSelectionWindow window = new ProjectSelectionWindow(envType, gameVersion, onFinish);
			window.setVisible(true);
			return;
		}
		start(envType, gameVersion, onFinish);
	}

	public static void start(EnvType envType, String gameVersion, Runnable onFinish) {
		instance = new Updater(Config.projectId, gameVersion, Config.authToken, Config.alwaysOverrideFiles, envType);
		instance.checkForUpdates();
		if (instance.startGame)
			onFinish.run();
	}

	public static OkHttpClient createHttpClient(String authToken) {
		return new OkHttpClient.Builder()
				.addInterceptor(chain -> {
					Request.Builder builder = chain.request().newBuilder()
							.addHeader("User-Agent", "Lexoland/mrpack-fabric-loader");
					if (authToken != null && !authToken.isEmpty())
						builder.addHeader("Authorization", authToken);
					return chain.proceed(builder.build());
				}).build();
	}

	public static Updater getInstance() {
		return instance;
	}
}
