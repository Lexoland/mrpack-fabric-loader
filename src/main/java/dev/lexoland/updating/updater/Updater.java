package dev.lexoland.updating.updater;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Enumeration;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.lexoland.updating.config.Config;
import dev.lexoland.updating.rendering.UpdateRenderer;
import dev.lexoland.updating.rendering.UpdateWindow;
import dev.lexoland.updating.rendering.stages.CreateBackupStage;
import dev.lexoland.updating.rendering.stages.DownloadPackFilesStage;
import dev.lexoland.updating.rendering.stages.DownloadPackMetaStage;
import dev.lexoland.updating.rendering.stages.ExtractOverridesStage;
import dev.lexoland.updating.rendering.stages.FinishUpStage;
import dev.lexoland.updating.rendering.stages.RestoreBackupStage;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import javax.swing.*;

public class Updater {

	public static final String MR_ENDPOINT = "https://api.modrinth.com/v2";
	public static final Gson GSON = new Gson();

	public static final File UPDATER_DIR = new File("updater");
	public static final File USER_CONFIG_FILE = new File(UPDATER_DIR, "config.json");
	public static final File PACK_FILE = new File(UPDATER_DIR, "pack.mrpack");

	private static final ImmutableList<String> DOWNLOAD_DOMAIN_WHITELIST = ImmutableList.of(
			"cdn.modrinth.com",
			"github.com",
			"raw.githubusercontent.com",
			"gitlab.com"
	);

	private static Updater instance;


	private final BackupHandler backupHandler;
	private final DownloadHandler downloadHandler;


	private final String projectId;
	private final String gameVersion;
	private final Collection<String> alwaysOverrideFiles;
	private final EnvType environment;

	int totalEntries;
	int currentEntry;
	String entryName;

	private boolean startGame = true;

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
		this.backupHandler = new BackupHandler(this);
		this.downloadHandler = new DownloadHandler(authToken);
	}

	public void checkForUpdates(Runnable onFinish) {
		backupHandler.loadPreviousInstallationInfo();
		String currentVersionNumber = backupHandler.getPreviousVersionNumber();

		Log.info(LogCategory.UPDATER, "Current pack version: %s", currentVersionNumber == null ? "None" : currentVersionNumber);

		Request request = new Request.Builder()
				.url(MR_ENDPOINT + "/project/" + projectId + "/version?loaders=[\"fabric\"]&game_versions=[\"" + gameVersion + "\"]")
				.get()
				.build();

		try (Response response = downloadHandler.requestVersion(projectId, gameVersion)) {
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

			UpdateWindow.open(environment, gameVersion, onFinish);

			Log.info(LogCategory.UPDATER, "Creating backup...");
			UpdateRenderer.setStage(new CreateBackupStage(this));
			backupHandler.createBackup();

			// get primary file
			UpdateRenderer.setStage(new DownloadPackMetaStage(this));

			JsonObject file = StreamSupport.stream(version.getAsJsonArray("files").spliterator(), false)
					.map(JsonElement::getAsJsonObject)
					.filter(f -> f.get("primary").getAsBoolean())
					.findAny()
					.orElseThrow(() -> new RuntimeException("No primary file found"));

			String fileName = file.get("filename").getAsString();
			String downloadUrl = file.get("url").getAsString();

			downloadHandler.requestFileDownload(fileName, PACK_FILE, HttpUrl.get(downloadUrl));
			downloadHandler.downloadEnqueuedFiles(() -> {});
			downloadPackFiles();
			extractOverrides();

			UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Removed Pack Files...", 0, 3));
			backupHandler.deleteRemovedPackFiles();

			UpdateRenderer.setStage(new FinishUpStage(this, "Saving Installation Info...", 1, 3));
			backupHandler.setNewVersionNumber(newVersionNumber);
			backupHandler.saveNewInstallationInfo();
		} catch (Exception e) {
			Log.error(LogCategory.UPDATER, "Failed to check/update pack.", e);
			showUpdateFailedOptionPane(e);

			if (backupHandler.hasBackedUp()) {
				Log.info(LogCategory.UPDATER, "Deleting installation files...");
				UpdateRenderer.setStage(new RestoreBackupStage(this, false));
				backupHandler.cleanUpInstallation();
				Log.info(LogCategory.UPDATER, "Restoring backup...");
				UpdateRenderer.setStage(new RestoreBackupStage(this, true));
				backupHandler.restoreBackup();
			}
		} finally {
			if(backupHandler.needsCleanup()) {
				UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Cache...", 2, 3));
				backupHandler.cleanUpBackup();
				UpdateRenderer.setStage(new FinishUpStage(this, "Deleting Cache...", 3, 3));
			}
			downloadHandler.close();
			UpdateWindow.close();
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
			downloadHandler.requestFileDownload(destination.getName(), destination, downloadUrl);
		}
		downloadHandler.downloadEnqueuedFiles(() -> currentEntry++);
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

		try {
			entryName = destinationPath;
			File destination = new File(destinationPath);
			String name = destination.getName();

			markAsInstalled(destination);

			if (destination.exists() && alwaysOverrideFiles.stream().noneMatch(name::endsWith))
				return;

			InputStream in = file.getInputStream(entry);

			File parent = destination.getParentFile();
			if (parent != null && !parent.exists())
				if(!parent.mkdirs())
					throw new IOException("Failed to create directory " + parent);

			Files.asByteSink(destination).writeFrom(in);
		} finally {
			entryName = null;
		}
	}

	private void markAsInstalled(File file) {
		backupHandler.addNewFile(file.getPath());
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
		String message = backupHandler.hasBackedUp()
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
		DownloadHandler.FileDownload download = downloadHandler.currentDownload();
		if (download == null)
			return 0;
		return download.downloaded();
	}

	public long getDownloadSize() {
		DownloadHandler.FileDownload download = downloadHandler.currentDownload();
		if (download == null)
			return 1;
		return download.downloadSize();
	}

	public long getDownloadSpeed() {
		return downloadHandler.downloadSpeed();
	}

	public String getEntryName() {
		if(entryName != null)
			return entryName;
		DownloadHandler.FileDownload download = downloadHandler.currentDownload();
		if (download == null)
			return null;
		return download.fileName();
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
			UpdateWindow.open(envType, gameVersion, onFinish);
			return;
		}
		start(envType, gameVersion, onFinish);
	}

	public static void start(EnvType envType, String gameVersion, Runnable onFinish) {
		instance = new Updater(Config.projectId, gameVersion, Config.authToken, Config.alwaysOverrideFiles, envType);
		instance.checkForUpdates(onFinish);
		if (instance.startGame)
			onFinish.run();
	}

	public static Updater getInstance() {
		return instance;
	}
}
