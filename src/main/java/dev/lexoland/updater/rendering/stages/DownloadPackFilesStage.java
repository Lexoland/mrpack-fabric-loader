package dev.lexoland.updater.rendering.stages;

import dev.lexoland.updater.Updater;

import java.awt.*;

public class DownloadPackFilesStage extends UpdateStage {

	public DownloadPackFilesStage(Updater updater) {
		super(updater, "Downloading...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		int barGap = 90;
		int y = middleY() - (PROGRESS_BAR_HEIGHT * 2 + barGap) / 2;

		renderProgressBar(
				graphics, y,
				(double) currentEntry() / totalEntries(),
				"Downloading Pack Files...", null, currentEntry() + "/" + totalEntries()
		);
		renderProgressBar(
				graphics, y + barGap,
				(double) downloaded() / downloadSize(),
				"Downloading " + entryName() + "...", downloadSpeedFormatted(), downloadProgressFormatted()
		);
	}
}
