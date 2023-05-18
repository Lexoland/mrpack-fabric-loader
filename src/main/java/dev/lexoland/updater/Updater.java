package dev.lexoland.updater;

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
import java.util.concurrent.atomic.AtomicBoolean;
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
import dev.lexoland.updater.rendering.ProgressBar;
import dev.lexoland.updater.rendering.UpdateRenderer;
import dev.lexoland.updater.rendering.UpdateWindow;
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


	private final OkHttpClient client;

	private final String projectId;
	private final String gameVersion;
	private final Collection<String> alwaysOverrideFiles;
	private final EnvType environment;

	private long downloadSize;
	private long downloaded;

	private boolean backupCreated = false;
	private boolean needsCleanup = true;
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
				needsCleanup = false;
				throw new IOException("Unexpected code " + response);
			}

			JsonArray versions = GSON.fromJson(response.body().charStream(), JsonArray.class);

			if (versions.size() == 0) {
				Log.warn(LogCategory.UPDATER, "No versions found");
				needsCleanup = false;
				return;
			}

			JsonObject version = versions.get(0).getAsJsonObject();
			String newVersionNumber = version.get("version_number").getAsString();

			if (newVersionNumber.equals(currentVersionNumber)) {
				Log.info(LogCategory.UPDATER, "Pack is up to date");
				needsCleanup = false;
				return;
			}
			Log.info(LogCategory.UPDATER, "New version found: %s", newVersionNumber);

			createBackup();

			UpdateWindow.setHeader("Downloading..");
			UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.DOWNLOADING_PACK_META);

			// get primary file
			JsonObject file = StreamSupport.stream(version.getAsJsonArray("files").spliterator(), false)
					.map(JsonElement::getAsJsonObject)
					.filter(f -> f.get("primary").getAsBoolean())
					.findAny()
					.orElseThrow(() -> {
						UpdateWindow.error("No primary file found", -101);
						return new RuntimeException("No primary file found");
					});

			String fileName = file.get("filename").getAsString();
			String downloadUrl = file.get("url").getAsString();

			downloadFile(fileName, HttpUrl.get(downloadUrl), PACK_FILE, true);
			downloadPackFiles();
			extractOverrides();

			deleteRemovedPackFiles();

			newInstallationInfo.versionNumber = newVersionNumber;
			saveNewInstallationInfo();
		} catch (Exception e) {
			Log.error(LogCategory.UPDATER, "Failed to check/update pack", e);
			if (backupCreated) {
				restoreBackup();
			} else UpdateWindow.error("Failed to check/update pack", -103);
		} finally {
			if(needsCleanup)
				cleanUp();
		}
	}


	private void downloadFile(String fileName, HttpUrl downloadUrl, File destination, boolean meta) throws IOException {
		Log.info(LogCategory.UPDATER, "Downloading '%s'...", fileName);

		File parent = destination.getParentFile();
		if (parent != null && !parent.exists())
			parent.mkdirs();

		Request request = new Request.Builder()
				.url(downloadUrl)
				.get()
				.build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				UpdateWindow.error("Failed to download file", response.code());
				throw new IOException("Unexpected code " + response);
			}

			ResponseBody body = response.body();
			downloadSize = body.contentLength();
			downloaded = 0;
			ProgressBar bar = UpdateWindow.getBar(0)
					.status(Lazy.of(() -> String.format("Downloading %s..", fileName)))
					.rightText(Lazy.of(() -> downloaded), Lazy.of(() -> downloadSize));
			if(!meta)
				bar.leftText(Lazy.of(() -> String.format("From: %s", downloadUrl)));

			InputStream in = body.byteStream();
			FileOutputStream out = new FileOutputStream(destination);

			byte[] buffer = new byte[1024];
			int read;

			final AtomicBoolean downloading = new AtomicBoolean(true);
			new Thread(() -> {
				long prevDownloaded = 0;
				ProgressBar progressBar = UpdateWindow.getBar(meta ? 0 : 1);
				while(downloading.get()) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
					long finalPrevDownloaded = prevDownloaded;
					progressBar.leftText(Lazy.of(() -> String.format("%.2f MB/s", (downloaded - finalPrevDownloaded) / 1024.0 / 1024.0)));
					prevDownloaded = downloaded;
				}
			}, "Download-Analyser").start();

			while ((read = in.read(buffer, 0, 1024)) != -1) {
				out.write(buffer, 0, read);
				downloaded += read;
				bar.progress((float) downloaded / downloadSize)
					.rightText(Lazy.of(() -> downloaded), Lazy.of(() -> downloadSize));
			}
			downloading.set(false);
			out.close();
		}
	}

	private void downloadPackFiles() throws IOException {
		Log.info(LogCategory.UPDATER, "Downloading pack files...");
		UpdateWindow.setHeader("Downloading..");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.DOWNLOADING_PACK_FILES);

		JsonObject index = getPackIndex();
		JsonArray files = index.getAsJsonArray("files");

		ProgressBar bar = UpdateWindow.getBar(1).status(Lazy.of(() -> "Downloading files..."));

		for (int i = 0; i < files.size(); i++) {
			JsonObject file = files.get(0).getAsJsonObject();
			int finalI = i;
			bar.progress((float) i / files.size() - 1)
				.rightText(Lazy.of(() -> finalI + "/" + files.size()));

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
				bar.leftText(Lazy.of(() -> "Skipping file '" + path + "' because it has an invalid path"));
				continue;
			}
			File destination = new File(path);

			JsonArray downloads = file.getAsJsonArray("downloads");
			if (downloads.size() == 0)
				continue;
			HttpUrl downloadUrl = HttpUrl.get(downloads.get(0).getAsString());
			if (!DOWNLOAD_DOMAIN_WHITELIST.contains(downloadUrl.host())) {
				Log.warn(LogCategory.UPDATER, "Skipping file '%s' because it is hosted on an invalid domain (%s)", path, downloadUrl.host());
				bar.leftText(Lazy.of(() -> "Skipping file '" + path + "' because it is hosted on an invalid domain (" + downloadUrl.host() + ")"));
				continue;
			}

			String hash = file.getAsJsonObject("hashes").get("sha512").getAsString();

			if (destination.exists()) {
				String localHash = Files.asByteSource(destination).hash(Hashing.sha512()).toString();
				if (localHash.equals(hash)) {
					markAsInstalled(destination);
					continue;
				}
			}

			markAsInstalled(destination);
			downloadFile(destination.getName(), downloadUrl, destination, false);
		}
	}

	private void deleteRemovedPackFiles() {
		UpdateWindow.setHeader("Cleaning up...");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.CLEANUP);
		ProgressBar bar = UpdateWindow.getBar(0).status(Lazy.of(() -> "Deleting removed files..."));
		for (int i = 0; i < previousInstallationInfo.files.size(); i++) {
			String installedFile = previousInstallationInfo.files.get(i);
			int finalI = i;
			bar.progress((float) i / previousInstallationInfo.files.size() - 1)
				.rightText(Lazy.of(() -> finalI + "/" + previousInstallationInfo.files.size()));
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
		UpdateWindow.setHeader("Extracting overrides...");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.EXTRACT_OVERRIDES);

		try (ZipFile zipFile = new ZipFile(PACK_FILE)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			ProgressBar bar = UpdateWindow.getBar(0).status(Lazy.of(() -> "Extracting files..."))
					.rightText(Lazy.of(() -> "0/" + zipFile.size()));

			int i = 0;
			int zipSize = zipFile.size();
			while (entries.hasMoreElements()) {
				int finalI = ++i;
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory()) {
					bar.progress((float) i / zipSize)
						.rightText(Lazy.of(() -> finalI + "/" + zipSize));
					continue;
				}

				if (entryName.startsWith("overrides/"))
					extractOverrideEntry(zipFile, entry, "overrides/");
				else if (entryName.startsWith("client-overrides/") && environment == EnvType.CLIENT)
					extractOverrideEntry(zipFile, entry, "client-overrides/");
				else if (entryName.startsWith("server-overrides/") && environment == EnvType.SERVER)
					extractOverrideEntry(zipFile, entry, "server-overrides/");
				bar.progress((float) i / zipFile.size())
						.rightText(Lazy.of(() -> finalI + "/" + zipSize));
			}
		}
	}

	private void extractOverrideEntry(ZipFile file, ZipEntry entry, String prefixToRemove) throws IOException {
		File destination = new File(entry.getName().substring(prefixToRemove.length()));
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
		UpdateWindow.setHeader("Creating backup...");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.CREATE_BACKUP);

		if (!UPDATER_DIR.exists())
			UPDATER_DIR.mkdirs();

		try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(BACKUP_FILE))) {
			if (INSTALLATION_INFO_FILE.exists()) {
				ZipEntry installationInfoEntry = new ZipEntry(INSTALLATION_INFO_FILE.getPath());
				out.putNextEntry(installationInfoEntry);
				Files.asByteSource(INSTALLATION_INFO_FILE).copyTo(out);
				out.closeEntry();
			}

			ProgressBar bar = UpdateWindow.getBar(0);
			bar.progress(0);

			for (int i = 0; i < previousInstallationInfo.files.size(); i++) {
				String installedFile = previousInstallationInfo.files.get(i);
				int finalI = i;
				bar.status(Lazy.of(() -> "Backing up " + installedFile))
						.progress((float) i / previousInstallationInfo.files.size() - 1)
						.rightText(Lazy.of(() -> String.format("Backing up.. %d/%d", finalI, previousInstallationInfo.files.size())));
				File file = new File(installedFile);
				if (!file.exists())
					continue;

				ZipEntry entry = new ZipEntry(file.getPath());
				out.putNextEntry(entry);
				Files.asByteSource(file).copyTo(out);
				out.closeEntry();
			}
		}
		backupCreated = true;
	}

	private void restoreBackup() {
		Log.info(LogCategory.UPDATER, "Restoring backup...");
		UpdateWindow.setHeader("Error! Restoring backup...");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.RESTORING_BACKUP);

		ProgressBar bar = UpdateWindow.getBar(0).status(Lazy.of(() -> "Deleting new files..."))
				.rightText(Lazy.of(() -> "0/" + previousInstallationInfo.files.size()));

		// delete all new files
		for (int i = 0; i < newInstallationInfo.files.size(); i++) {
			int finalI = i;
			bar.progress((float) i / newInstallationInfo.files.size() - 1)
					.rightText(Lazy.of(() -> finalI + "/" + newInstallationInfo.files.size()));
			String installedFile = newInstallationInfo.files.get(i);
			File file = new File(installedFile);

			if (!file.exists() || previousInstallationInfo.files.contains(installedFile))
				continue;

			file.delete();
		}

		bar.status(Lazy.of(() -> "Restoring backup files..")).progress(0).rightText(Lazy.of(() -> ""));

		// restore backup file
		try (ZipFile zipFile = new ZipFile(BACKUP_FILE)) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			bar.rightText(Lazy.of(() -> "0/" + zipFile.size()));

			int zipSize = zipFile.size();
			int i = 0;
			while (entries.hasMoreElements()) {
				int finalI = ++i;
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory()) {
					bar.progress((float) i / zipSize)
							.rightText(Lazy.of(() -> finalI + "/" + zipSize));
					continue;
				}

				File destination = new File(entryName);

				File parent = destination.getParentFile();
				if (parent != null && !parent.exists())
					parent.mkdirs();

				Files.asByteSink(destination).writeFrom(zipFile.getInputStream(entry));
				bar.progress((float) i / zipSize)
						.rightText(Lazy.of(() -> finalI + "/" + zipSize));
			}
			UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.CLOSE);
		} catch (IOException e) {
			Log.error(LogCategory.UPDATER, "Failed to restore backup", e);
			UpdateWindow.setHeader("Failed to restore backup!");
			UpdateWindow.error("Failed to restore backup!", -100);
		}
	}

	private void cleanUp() {
		Log.info(LogCategory.UPDATER, "Cleaning up...");
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.CLEANUP);
		UpdateWindow.getBar(0).status(Lazy.of(() -> "Cleaning up..."))
				.progress(1);

		if (BACKUP_FILE.exists())
			BACKUP_FILE.delete();

		if (PACK_FILE.exists())
			PACK_FILE.delete();
		UpdateWindow.advanceToStage(UpdateRenderer.ProgressStage.CLOSE);
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
			throw new RuntimeException("Failed to load installation info", e);
		}
	}

	private void saveNewInstallationInfo() {
		UpdateWindow.getBar(0).status(Lazy.of(() -> "Saving installation info...")).rightText(Lazy.of(() -> ""));
		try (FileWriter writer = new FileWriter(INSTALLATION_INFO_FILE)) {
			JsonObject installationInfoObject = new JsonObject();
			installationInfoObject.addProperty("version", newInstallationInfo.versionNumber);
			installationInfoObject.add("files", GSON.toJsonTree(newInstallationInfo.files));
			GSON.toJson(installationInfoObject, writer);
		} catch (IOException e) {
			UpdateWindow.error("Failed to save installation info!", -104);
			throw new RuntimeException("Failed to save installation info", e);
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
		UpdateWindow.error("No modrinth.index.json found in pack!", -105);
		throw new RuntimeException("No modrinth.index.json found in pack");
	}

	private static class InstallationInfo {
		private String versionNumber = null;
		private List<String> files = new ArrayList<>();
	}

	public static void launch(EnvType envType, Runnable onFinish) {
		Log.finishBuiltinConfig();

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
			Log.error(LogCategory.UPDATER, "Failed to set system look and feel", e);
		}

		Config.load();

		if (Config.shouldAskForProject()) {
			ProjectSelectionWindow window = new ProjectSelectionWindow(envType, onFinish);
			window.setVisible(true);
			return;
		}
		start(envType, onFinish);
	}

	public static void start(EnvType envType, Runnable onFinish) {
		Updater updater = new Updater(Config.projectId, Config.gameVersion, Config.authToken, Config.alwaysOverrideFiles, envType);
		UpdateWindow.setUpdater(updater);
		updater.checkForUpdates();
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
}
