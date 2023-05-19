package dev.lexoland.updater;

import net.fabricmc.api.EnvType;

public class Test {
	public static void main(String[] args) {
		Updater.launch(EnvType.CLIENT, "1.19.2", () -> System.out.println("Finished"));
	}
}
