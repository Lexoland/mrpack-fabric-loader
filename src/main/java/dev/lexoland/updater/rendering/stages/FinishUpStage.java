package dev.lexoland.updater.rendering.stages;

import dev.lexoland.updater.Updater;

import java.awt.*;

public class FinishUpStage extends UpdateStage {

	private final String message;
	private final int taskNumber, totalTasks;

	public FinishUpStage(Updater updater, String message, int taskNumber, int totalTasks) {
		super(updater, "Finishing...");
		this.message = message;
		this.taskNumber = taskNumber;
		this.totalTasks = totalTasks;
	}

	@Override
	public void render(Graphics2D graphics) {
		super.render(graphics);

		renderProgressBar(
				graphics, middleY() - PROGRESS_BAR_HEIGHT / 2,
				(double) taskNumber / totalTasks,
				message, null, taskNumber + "/" + totalTasks
		);
	}
}
