package dev.lexoland.updating.updater;

import static dev.lexoland.updating.updater.Updater.GSON;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.common.io.Files;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public class BackupHandler {

	private static final File UPDATER_DIR = Updater.UPDATER_DIR;
	private static final File PACK_FILE = Updater.PACK_FILE;
	private static final File BACKUP_FILE = new File(UPDATER_DIR, "backup.zip");
	private static final File INSTALLATION_INFO_FILE = new File(UPDATER_DIR, "installation-info.json");

	private final InstallationInfo previousInstallationInfo = new InstallationInfo();
	private final InstallationInfo newInstallationInfo = new InstallationInfo();

	private final Updater updater;

	private boolean needsCleanup;
	private boolean backupCreated;

	public BackupHandler(Updater updater) {
		this.updater = updater;
	}

	void loadPreviousInstallationInfo() {
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

	void saveNewInstallationInfo() {
		try (FileWriter writer = new FileWriter(INSTALLATION_INFO_FILE)) {
			JsonObject installationInfoObject = new JsonObject();
			installationInfoObject.addProperty("version", newInstallationInfo.versionNumber);
			installationInfoObject.add("files", GSON.toJsonTree(newInstallationInfo.files));
			GSON.toJson(installationInfoObject, writer);
		} catch (IOException e) {
			throw new RuntimeException("Failed to save installation info.", e);
		}
	}

	void createBackup() throws IOException {
		needsCleanup = true;

		updater.totalEntries = previousInstallationInfo.files.size() + 1;
		updater.currentEntry = 0;

		if (!UPDATER_DIR.exists())
			UPDATER_DIR.mkdirs();

		try (ZipOutputStream out = new ZipOutputStream(java.nio.file.Files.newOutputStream(BACKUP_FILE.toPath()))) {
			if (INSTALLATION_INFO_FILE.exists()) {
				updater.entryName = INSTALLATION_INFO_FILE.getPath();

				ZipEntry installationInfoEntry = new ZipEntry(INSTALLATION_INFO_FILE.getPath());
				out.putNextEntry(installationInfoEntry);
				Files.asByteSource(INSTALLATION_INFO_FILE).copyTo(out);
				out.closeEntry();

				updater.currentEntry++;
			}

			for (int i = 0; i < previousInstallationInfo.files.size(); i++) {
				String installedFile = previousInstallationInfo.files.get(i);

				updater.entryName = installedFile;

				File file = new File(installedFile);
				if (!file.exists())
					continue;

				ZipEntry entry = new ZipEntry(file.getPath());
				out.putNextEntry(entry);
				Files.asByteSource(file).copyTo(out);
				out.closeEntry();

				updater.currentEntry++;
			}
		} finally {
			updater.entryName = null;
		}
		backupCreated = true;
	}

	void cleanUpInstallation() {
		// delete all new files

		List<File> toDelete = newInstallationInfo.files.stream()
				.filter(file -> !previousInstallationInfo.files.contains(file))
				.map(File::new)
				.filter(File::exists)
				.collect(Collectors.toList());

		updater.totalEntries = toDelete.size();
		updater.currentEntry = 0;

		for (File file : toDelete) {
			file.delete();
			updater.currentEntry++;
		}
	}

	void restoreBackup() {
		// restore backup

		try (ZipFile zipFile = new ZipFile(BACKUP_FILE)) {
			updater.totalEntries = zipFile.size();
			updater.currentEntry = 0;

			Enumeration<? extends ZipEntry> entries = zipFile.entries();


			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String entryName = entry.getName();

				if (entry.isDirectory()) {
					updater.currentEntry++;
					continue;
				}

				File destination = new File(entryName);

				File parent = destination.getParentFile();
				if (parent != null && !parent.exists())
					parent.mkdirs();

				Files.asByteSink(destination).writeFrom(zipFile.getInputStream(entry));

				updater.currentEntry++;
			}
		} catch (IOException e) {
			Log.error(LogCategory.UPDATER, "Failed to restore backup.", e);
		}
	}

	void cleanUpBackup() {
		Log.info(LogCategory.UPDATER, "Cleaning up...");

		if (BACKUP_FILE.exists())
			BACKUP_FILE.delete();

		if (PACK_FILE.exists())
			PACK_FILE.delete();
	}

	void deleteRemovedPackFiles() {
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

	void addNewFile(String path) {
		newInstallationInfo.files.add(path);
	}

	boolean hasBackedUp() {
		return backupCreated;
	}

	boolean needsCleanup() {
		return needsCleanup;
	}

	String getPreviousVersionNumber() {
		return previousInstallationInfo.versionNumber;
	}

	void setNewVersionNumber(String number) {
		newInstallationInfo.versionNumber = number;
	}

	private static class InstallationInfo {
		private String versionNumber = null;
		private List<String> files = new ArrayList<>();
	}
}
