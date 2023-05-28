package dev.lexoland.updater;

import net.fabricmc.api.EnvType;

public class Test {

	/**
	 * Test main method
	 * Used to test the updater without having to run the game
	 * @param args - Arguments
	 */
	public static void main(String[] args) {
		Updater.launch(EnvType.CLIENT, "1.19.2", () -> System.out.println("Finished"));
	}
}
