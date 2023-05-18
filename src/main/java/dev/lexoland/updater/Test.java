package dev.lexoland.updater;

import net.fabricmc.api.EnvType;

public class Test {
	public static void main(String[] args) {
		Updater.launch(EnvType.CLIENT, () -> System.out.println("Finished"));
	}
}
