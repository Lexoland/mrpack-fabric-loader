package dev.lexoland.updater.config;

import java.awt.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.lexoland.updater.Updater;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.util.log.Log;

import net.fabricmc.loader.impl.util.log.LogCategory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProjectSelectionWindow extends JFrame {

	private static final Gson GSON = new Gson();

	private JTextField projectIdField;
	private JPasswordField authTokenField;

	public ProjectSelectionWindow(EnvType envType, Runnable onFinish) {
		super("Select Modrinth Pack");

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		JPanel root = new JPanel();
		root.setLayout(new GridBagLayout());
		root.setBorder(new EmptyBorder(5, 5, 5, 5));
		setContentPane(root);

		GridBagConstraints c = new GridBagConstraints();

		c.insets = new Insets(2, 2, 2, 2);

		c.anchor = GridBagConstraints.EAST;
		c.gridx = 0;
		c.fill = GridBagConstraints.NONE;
		root.add(new JLabel("Project Slug/ID:"), c);

		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		projectIdField = new JTextField(Config.projectId, 20);
		root.add(projectIdField, c);


		c.anchor = GridBagConstraints.EAST;
		c.gridx = 0;
		c.gridy = 1;
		c.fill = GridBagConstraints.NONE;
		root.add(new JLabel("Auth. Token (optional):"), c);

		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		authTokenField = new JPasswordField(Config.authToken, 20);
		root.add(authTokenField, c);


		c.gridwidth = 2;
		c.gridx = 0;
		c.gridy = 2;
		c.weightx = 1;
		c.insets = new Insets(5, 2, 2, 2);
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));

		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> System.exit(0));
		buttonPanel.add(cancelButton);

		JButton okButton = new JButton("OK");
		okButton.addActionListener(e -> ok(root, envType, onFinish));
		buttonPanel.add(okButton);

		root.add(buttonPanel, c);

		pack();
		setLocationRelativeTo(null);
		setMinimumSize(getSize());
	}

	private void ok(JPanel root, EnvType envType, Runnable onFinish) {
		setPanelEnabled(root, false);

		new Thread(() -> {
			Config.projectId = projectIdField.getText();
			Config.authToken = new String(authTokenField.getPassword());

			try {
				HashSet<String> gameVersions = fetchProjectVersions();
				if (gameVersions == null) {
					error("Project not found or not authorized to access project.");
					setPanelEnabled(root, true);
					return;
				}
				if (gameVersions.isEmpty()) {
					error("Project has no versions available for fabric.");
					setPanelEnabled(root, true);
					return;
				}
				dispose();
				GameVersionSelectionWindow window = new GameVersionSelectionWindow(gameVersions, getMinimumSize(), envType, onFinish);
				window.setVisible(true);
			} catch (IOException ex) {
				Log.error(LogCategory.UPDATER, "Failed to fetch project versions", ex);
				error("Something went wrong while fetching project versions. See logs for more information.");
				setPanelEnabled(root, true);
			}
		}).start();
	}

	private void setPanelEnabled(JPanel panel, boolean enabled) {
		panel.setEnabled(enabled);
		Component[] components = panel.getComponents();
		for (Component component : components) {
			component.setEnabled(enabled);
			if (component instanceof JPanel)
				setPanelEnabled((JPanel) component, enabled);
		}
	}

	private void error(String message) {
		Toolkit.getDefaultToolkit().beep();
		JOptionPane.showMessageDialog(
				this,
				message,
				"Error",
				JOptionPane.ERROR_MESSAGE
		);
	}

	private HashSet<String> fetchProjectVersions() throws IOException {
		OkHttpClient client = Updater.createHttpClient(Config.authToken);

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

				List<String> gameVersions = GSON.fromJson(version.get("game_versions"), new TypeToken<ArrayList<String>>() {}.getType());
				List<String> loaders = GSON.fromJson(version.get("loaders"), new TypeToken<ArrayList<String>>() {}.getType());

				if (loaders.contains("fabric"))
					combinedVersions.addAll(gameVersions);
			}
		}
		return combinedVersions;
	}
}
