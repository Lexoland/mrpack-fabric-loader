package dev.lexoland.updater.rendering.stages;

import dev.lexoland.updater.Updater;

import java.awt.*;

public class CreateBackupStage extends UpdateStage {

	public CreateBackupStage(Updater updater) {
		super(updater, "Creating Backup...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) currentEntry() / totalEntries(),
				"Zipping " + entryName(), null, currentEntry() + "/" + totalEntries()
		);
	}
}
