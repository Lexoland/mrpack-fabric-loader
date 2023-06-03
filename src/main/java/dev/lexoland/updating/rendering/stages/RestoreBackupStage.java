package dev.lexoland.updating.rendering.stages;

import dev.lexoland.updating.updater.Updater;

import java.awt.*;

public class RestoreBackupStage extends UpdateStage {

	private final boolean unzipping;

	private final ProgressBar progressBar = new ProgressBar();

	public RestoreBackupStage(Updater updater, boolean unzipping) {
		super(updater, "Restoring Backup...", new Color(0xC45A03), new Color(0xDAB600));
		this.unzipping = unzipping;
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		if (!unzipping) {
			progressBar.renderProgressBar(
					graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
					((double) currentEntry() / totalEntries()) * 0.5,
					"Deleting " + entryName() + "...", null, currentEntry() + "/" + totalEntries()
			);
		} else {
			progressBar.renderProgressBar(
					graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
					0.5 + ((double) currentEntry() / totalEntries()) * 0.5,
					"Extracting " + entryName() + "...", null, currentEntry() + "/" + totalEntries()
			);
		}
	}
}
