package dev.lexoland.updater.rendering.stages;

import dev.lexoland.updater.Updater;

import java.awt.*;

public class ExtractOverridesStage extends UpdateStage {

	public ExtractOverridesStage(Updater updater) {
		super(updater, "Extracting Overrides...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) currentEntry() / totalEntries(),
				"Extracting " + entryName() + "...", null, currentEntry() + "/" + totalEntries()
		);
	}
}
