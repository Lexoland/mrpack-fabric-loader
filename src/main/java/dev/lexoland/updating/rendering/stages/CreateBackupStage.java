package dev.lexoland.updating.rendering.stages;

import dev.lexoland.updating.updater.Updater;

import java.awt.*;

public class CreateBackupStage extends UpdateStage {

	private final ProgressBar progressBar = new ProgressBar();

	public CreateBackupStage(Updater updater) {
		super(updater, "Creating Backup...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		progressBar.renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) currentEntry() / totalEntries(),
				"Zipping " + entryName(), null, currentEntry() + "/" + totalEntries()
		);
	}
}
