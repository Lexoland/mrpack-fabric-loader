package dev.lexoland.updating.updater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.NotNull;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public class DownloadHandler {

	private static final String MR_ENDPOINT = "https://api.modrinth.com/v2";

	private final OkHttpClient client;

	private final BlockingQueue<FileDownload> fileDownloads = new LinkedBlockingQueue<>();

	private FileDownload currentDownload;

	private boolean downloading = false;

	private long downloadSpeedTimestamp = 0;
	private long downloadSpeedCounter = 0;
	private long downloadSpeed = 0;

	private int enqueued = 0;

	public DownloadHandler(String authToken) {
		this.client = createHttpClient(authToken);
	}

	public static OkHttpClient createHttpClient(String authToken) {
		return new OkHttpClient.Builder()
				.readTimeout(Duration.ZERO)
				.protocols(Arrays.asList(Protocol.QUIC, Protocol.HTTP_1_1))
				.addInterceptor(chain -> {
					Request.Builder builder = chain.request().newBuilder()
							.addHeader("User-Agent", "Lexoland/mrpack-fabric-loader");
					if (authToken != null && !authToken.isEmpty())
						builder.addHeader("Authorization", authToken);
					return chain.proceed(builder.build());
				}).build();
	}

	void close() {
		client.dispatcher().executorService().shutdown();
		client.connectionPool().evictAll();
	}

	Response requestVersion(String projectId, String gameVersion) throws IOException {
		Request request = new Request.Builder()
				.url(MR_ENDPOINT + "/project/" + projectId + "/version?loaders=[\"fabric\"]&game_versions=[\"" + gameVersion + "\"]")
				.get()
				.build();

		Response response = client.newCall(request).execute();

		if (!response.isSuccessful())
			throw new IOException("Unexpected code " + response);
		return response;
	}

	void requestFileDownload(String fileName, File packFile, HttpUrl downloadUrl) {
		if(downloading)
			throw new IllegalStateException("Cannot enqueue downloads while downloading");
		Request request = new Request.Builder()
				.url(downloadUrl)
				.get()
				.build();

		enqueued++;
		client.newCall(request).enqueue(new CallbackHandler(fileName, packFile));
	}

	void downloadEnqueuedFiles(Runnable callback) throws IOException {
		if (downloading)
			throw new IllegalStateException("Already downloading");
		downloading = true;
		while (enqueued > 0 || !fileDownloads.isEmpty()) {
			try {
				currentDownload = fileDownloads.take();
				currentDownload.start();
				callback.run();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		downloading = false;
		enqueued = 0;
	}

	FileDownload currentDownload() {
		return currentDownload;
	}

	long downloadSpeed() {
		return downloadSpeed;
	}

	class FileDownload {

		private final String fileName;
		private final File destination;
		private final ResponseBody responseBody;

		private long downloadSize = -1;
		private long downloaded = 0;

		public FileDownload(String fileName, File destination, ResponseBody body) {
			this.fileName = fileName;
			this.destination = destination;
			this.responseBody = body;
		}

		public void start() throws IOException {
			File parent = destination.getParentFile();
			if (parent != null && !parent.exists())
				if(!parent.mkdirs())
					throw new IOException("Failed to create directory " + parent);

			Log.info(LogCategory.UPDATER, "Downloading '%s'...", fileName);
			downloaded = 0;
			downloadSize = responseBody.contentLength();

			InputStream in = responseBody.byteStream();
			try (FileOutputStream out = new FileOutputStream(destination)) {
				byte[] buffer = new byte[1024];
				int read;

				while ((read = in.read(buffer, 0, 1024)) != -1) {
					out.write(buffer, 0, read);
					downloaded += read;
					downloadSpeedCounter += read;

					long now = System.currentTimeMillis();
					if (now - downloadSpeedTimestamp > 1000) {
						downloadSpeedTimestamp = now;
						downloadSpeed = downloadSpeedCounter;
						downloadSpeedCounter = 0;
					}
				}
			}
			Log.info(LogCategory.UPDATER, "Finished downloading '%s'", fileName);
		}

		public String fileName() {
			return fileName;
		}

		public long downloadSize() {
			return downloadSize;
		}

		public long downloaded() {
			return downloaded;
		}
	}

	private class CallbackHandler implements Callback {

		private static final int MAX_TRIES = 5;

		private final String fileName;
		private final File destination;

		private int tries = 0;

		public CallbackHandler(String fileName, File destination) {
			this.fileName = fileName;
			this.destination = destination;
		}

		@Override
		public void onFailure(@NotNull Call call, @NotNull IOException e) {
			if (tries++ < MAX_TRIES) {
				call.clone().enqueue(this);
				Log.warn(LogCategory.UPDATER, "Failed to download file: " + call.request().url() + ", retrying...", e);
				return;
			}
			enqueued--;
			Log.error(LogCategory.UPDATER, "Failed to download file: " + call.request().url(), e);
		}

		@Override
		public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
			if (!response.isSuccessful()) {
				if (tries++ < MAX_TRIES) {
					call.clone().enqueue(this);
					return;
				}
				throw new IOException("Unexpected code " + response);
			}
			fileDownloads.add(new FileDownload(fileName, destination, response.body()));
			enqueued--;
		}
	}
}
