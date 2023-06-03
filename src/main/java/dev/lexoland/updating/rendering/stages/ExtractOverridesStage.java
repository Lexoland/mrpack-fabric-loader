package dev.lexoland.updating.rendering.stages;

import dev.lexoland.updating.updater.Updater;

import java.awt.*;

public class ExtractOverridesStage extends UpdateStage {

	private final ProgressBar progressBar = new ProgressBar();

	public ExtractOverridesStage(Updater updater) {
		super(updater, "Extracting Overrides...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		progressBar.renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) currentEntry() / totalEntries(),
				"Extracting " + entryName() + "...", null, currentEntry() + "/" + totalEntries()
		);
	}
}
