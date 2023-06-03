package dev.lexoland.updating.rendering.stages;

import dev.lexoland.updating.updater.Updater;

import java.awt.*;

public class DownloadPackMetaStage extends UpdateStage {

	private final ProgressBar progressBar = new ProgressBar();

	public DownloadPackMetaStage(Updater updater) {
		super(updater, "Downloading...");
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		progressBar.renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) downloaded() / downloadSize(),
				"Downloading Pack Meta...", downloadSpeedFormatted(), downloadProgressFormatted()
		);
	}
}
