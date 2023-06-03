package dev.lexoland.updating.rendering.stages;

import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.lexoland.updating.updater.DownloadHandler;
import dev.lexoland.updating.updater.Updater;
import dev.lexoland.updating.config.Config;
import dev.lexoland.updating.rendering.UpdateRenderer;

import dev.lexoland.updating.rendering.UpdateWindow;
import dev.lexoland.updating.rendering.utils.Direction;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.swing.*;

public class ProjectSelectionStage extends UpdateStage {

	private static final Gson GSON = new Gson();
	private static final Font ERROR_MESSAGE_FONT = PROGRESS_BAR_STATUS_FONT.deriveFont(14f);
	private static final int ERROR_MESSAGE_TIME = 10;

	private final UpdateRenderer renderer;

	private final ConfirmationButton okButton;
	private final JTextField projectIdField;
	private final JPasswordField authTokenField;

	private String errorMessage = "";
	private long errorTime;

	public ProjectSelectionStage(UpdateRenderer renderer, EnvType envType, String gameVersion, Runnable onFinish, Runnable onSelected) {
		super(null, "Select Project");
		this.renderer = renderer;

		KeyAdapter enterListener = new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER)
					ok(envType, gameVersion, onFinish, onSelected);
			}
		};

		renderer.setLayout(new GridBagLayout());

		GridBagConstraints c = new GridBagConstraints();

		c.insets = new Insets(2, 2, 20, 2);
		c.anchor = GridBagConstraints.CENTER;

		JLabel title = new JLabel("Select Project");
		title.setFont(HEADER_FONT);
		title.setForeground(Color.WHITE);
		renderer.add(title, c);

		c.insets = new Insets(2, 2, 2, 2);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		c.gridy = 1;

		JLabel slug = new JLabel("Project Slug/ID:");
		slug.setForeground(Color.WHITE);
		slug.setFont(PROGRESS_BAR_STATUS_FONT);
		renderer.add(slug, c);

		c.gridy = 2;
		c.fill = GridBagConstraints.HORIZONTAL;
		projectIdField = new JTextField(Config.projectId, 20);
		projectIdField.setCaretPosition(projectIdField.getText().length());
		projectIdField.setForeground(Color.WHITE);
		projectIdField.setBackground(DEFAULT_PRIMARY_BACKGROUND_COLOR);
		projectIdField.setCaretColor(Color.WHITE);
		projectIdField.setFont(PROGRESS_BAR_STATUS_FONT);
		projectIdField.addKeyListener(enterListener);
		renderer.add(projectIdField, c);

		c.insets = new Insets(28, 2, 2, 2);

		c.anchor = GridBagConstraints.WEST;
		c.gridy = 3;
		c.fill = GridBagConstraints.NONE;

		JLabel token = new JLabel("Auth. Token (optional):");
		token.setForeground(Color.WHITE);
		token.setFont(PROGRESS_BAR_STATUS_FONT);
		renderer.add(token, c);

		c.insets = new Insets(2, 2, 2, 2);

		c.gridy = 4;
		c.fill = GridBagConstraints.HORIZONTAL;
		authTokenField = new JPasswordField(Config.authToken, 20);
		authTokenField.setForeground(Color.WHITE);
		authTokenField.setBackground(DEFAULT_PRIMARY_BACKGROUND_COLOR);
		authTokenField.setCaretColor(Color.WHITE);
		authTokenField.setFont(authTokenField.getFont().deriveFont((float) PROGRESS_BAR_STATUS_FONT.getSize()));
		authTokenField.addKeyListener(enterListener);
		renderer.add(authTokenField, c);


		c.anchor = GridBagConstraints.CENTER;
		c.gridy = 5;
		c.ipadx = 150;
		c.ipady = 5;
		c.fill = GridBagConstraints.NONE;
		c.insets = new Insets(15, 2, 2, 2);

		okButton = new ConfirmationButton("Ok", () -> ok(envType, gameVersion, onFinish, onSelected));
		okButton.setBorder(authTokenField.getBorder());
		okButton.setForeground(Color.WHITE);
		okButton.setFont(PROGRESS_BAR_STATUS_FONT);
		renderer.add(okButton, c);
	}

	@Override
	public void render(Graphics2D graphics) {
		renderGradientBackground(graphics, primaryColor, secondaryColor);

		if(errorMessage.isEmpty())
			return;
		long time = (System.currentTimeMillis() - errorTime) / 1000;
		if(time == ERROR_MESSAGE_TIME) {
			errorMessage = "";
			return;
		}

		graphics.setColor(Color.RED);
		graphics.setFont(ERROR_MESSAGE_FONT);
		graphics.drawString(
				errorMessage + " [" + (ERROR_MESSAGE_TIME - time) + "]",
				(screenWidth - graphics.getFontMetrics().stringWidth(errorMessage + " [" + (ERROR_MESSAGE_TIME - time) + "]")) / 2,
				screenHeight - graphics.getFontMetrics().getHeight() / 2
		);
	}

	private void ok(EnvType envType, String gameVersion, Runnable onFinish, Runnable onSelected) {
		okButton.setEnabled(false);
		if (projectIdField.getText().isEmpty()) {
			error("Please enter a project slug/id.");
			return;
		}

		new Thread(() -> {
			Config.projectId = projectIdField.getText();
			Config.authToken = new String(authTokenField.getPassword());

			try {
				HashSet<String> gameVersions = fetchProjectVersions();

				if (gameVersions == null) {
					error("Project not found or not authorized to access project.");
					return;
				}

				if (!gameVersions.contains(gameVersion)) {
					error("Project has no versions available for mc" + gameVersion + " using fabric loader.");
					return;
				}


				Config.save();

				new Thread(() -> {
					onSelected.run();
					renderer.removeAll();
					UpdateWindow.getInstance().setTitle("Updating...");
					UpdateRenderer.setStage(new PreparingDownloadStage());
					Updater.start(envType, gameVersion, onFinish);
				}).start();
			} catch (IOException ex) {
				Log.error(LogCategory.UPDATER, "Failed to fetch project versions", ex);
				error("Something went wrong while fetching project versions. See logs for more information.");
			}
		}).start();
	}

	private HashSet<String> fetchProjectVersions() throws IOException {
		OkHttpClient client = DownloadHandler.createHttpClient(Config.authToken);

		Request request = new Request.Builder()
				.url(Updater.MR_ENDPOINT + "/project/" + Config.projectId + "/version")
				.get()
				.build();

		HashSet<String> combinedVersions = new HashSet<>();

		try (Response response = client.newCall(request).execute()) {

			if (response.code() == 404)
				return null;

			if (!response.isSuccessful())
				throw new IOException("Unexpected code " + response);

			InputStreamReader reader = new InputStreamReader(response.body().byteStream());
			JsonArray versions = GSON.fromJson(reader, JsonArray.class);

			for (JsonElement versionElement : versions) {
				JsonObject version = versionElement.getAsJsonObject();

				java.util.List<String> gameVersions = GSON.fromJson(version.get("game_versions"), new TypeToken<ArrayList<String>>() {}.getType());
				List<String> loaders = GSON.fromJson(version.get("loaders"), new TypeToken<ArrayList<String>>() {}.getType());

				if (loaders.contains("fabric"))
					combinedVersions.addAll(gameVersions);
			}
		}
		return combinedVersions;
	}

	private void error(String message) {
		errorMessage = message;
		errorTime = System.currentTimeMillis();
		okButton.setEnabled(true);
	}

	private static class PreparingDownloadStage extends UpdateStage {

		private static final int QUAD_MIN_SIZE = 4;
		private static final int QUAD_MAX_SIZE = 13;
		private static final int GRID_SIZE = 34;

		public PreparingDownloadStage() {
			super(null, "Preparing download...");
		}

		@Override
		public void render(Graphics2D graphics) {
			super.render(graphics);

			int xOff = (screenWidth - GRID_SIZE * 2) / 2;
			int yOff = (screenHeight - GRID_SIZE * 2) / 2;

			Direction direction = Direction.NORTH;
			int x = 0;
			int y = 0;
			for (int i = 1; i < 9; i++) {
				x += direction.x();
				y += direction.y();
				if(i % 2 == 0)
					direction = direction.next();

				double sin = Math.sin(System.currentTimeMillis() / 100.0 + i / 8.0 * Math.PI * 2) / 2 + 0.5;
				int size = (int) (Math.round(QUAD_MIN_SIZE + sin * (QUAD_MAX_SIZE - QUAD_MIN_SIZE)));
				graphics.setColor(Color.WHITE);
				graphics.fillRect(
						xOff + x * GRID_SIZE - size,
						yOff + y * GRID_SIZE - size,
						size * 2, size * 2
				);
			}
		}
	}

	private static class ConfirmationButton extends JLabel {

		private boolean hover;
		private boolean pressed;

		public ConfirmationButton(String ok, Runnable onClick) {
			super(ok);
			this.addMouseListener(new MouseAdapter() {

				@Override
				public void mouseEntered(MouseEvent e) {
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hover = false;
					repaint();
				}

				@Override
				public void mousePressed(MouseEvent e) {
					pressed = true;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					pressed = false;
					repaint();
				}

				@Override
				public void mouseClicked(MouseEvent e) {
					if(isEnabled())
						onClick.run();
				}
			});
		}

		@Override
		public void paint(Graphics g) {
			Graphics2D graphics = (Graphics2D) g;

			Color c = DEFAULT_PRIMARY_BACKGROUND_COLOR;
			if(isEnabled()) {
				if (pressed) {
					c = c.darker();
				} else if (hover)
					c = c.brighter();
			} else c = c.darker();

			graphics.setColor(c);
			graphics.fillRect(1, 1, getWidth() - 2, getHeight() - 2);

			graphics.setColor(isEnabled() ? getForeground() : Color.GRAY);
			graphics.drawString(
					getText(),
					getWidth() / 2 - graphics.getFontMetrics().stringWidth(getText()) / 2,
					getHeight() / 2 + graphics.getFontMetrics().getHeight() / 2 - graphics.getFontMetrics().getDescent()
			);
			paintBorder(g);
		}
	}
}
