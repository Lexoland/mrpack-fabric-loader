package dev.lexoland.updater.config;

import com.google.gson.Gson;
import dev.lexoland.updater.Updater;

import net.fabricmc.api.EnvType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import java.util.HashSet;

public class GameVersionSelectionWindow extends JFrame {

	private static final Gson GSON = new Gson();

	private JComboBox<String> gameVersions;

	public GameVersionSelectionWindow(HashSet<String> versions, Dimension size, EnvType envType, Runnable onFinish) {
		super("Select Game Version");

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		JPanel root = new JPanel();
		root.setLayout(new GridBagLayout());
		root.setBorder(new EmptyBorder(5, 5, 5, 5));
		setContentPane(root);

		GridBagConstraints c = new GridBagConstraints();

		c.insets = new Insets(2, 2, 2, 2);

		c.anchor = GridBagConstraints.EAST;
		c.gridx = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		root.add(new JLabel("Game Version:"), c);

		c.gridx = 1;
		gameVersions = new JComboBox<>(versions.toArray(new String[0]));
		root.add(gameVersions, c);


		c.anchor = GridBagConstraints.SOUTHEAST;
		c.gridwidth = 2;
		c.gridx = 0;
		c.gridy = 2;
		c.weighty = 1;
		c.weightx = 1;
		c.insets = new Insets(5, 2, 2, 2);
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));

		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> System.exit(0));
		buttonPanel.add(cancelButton);

		JButton okButton = new JButton("OK");
		okButton.addActionListener(e -> {
			Config.gameVersion = (String) gameVersions.getSelectedItem();
			Config.save();
			dispose();
			Updater.start(envType, onFinish);
		});
		buttonPanel.add(okButton);

		root.add(buttonPanel, c);

		setSize(size);
		setLocationRelativeTo(null);
		setMinimumSize(size);
	}

}
