package com.xc.echominecart.ringvehicle;

/** Base performance families distinguished by ordinary versus powered-rail construction. */
public enum RingVehicleVariant {
	RAIL(false, 0.42D, 0.035D),
	POWERED(true, 0.92D, 0.065D);

	private final boolean powered;
	private final double maximumSpeed;
	private final double acceleration;

	RingVehicleVariant(boolean powered, double maximumSpeed, double acceleration) {
		this.powered = powered;
		this.maximumSpeed = maximumSpeed;
		this.acceleration = acceleration;
	}

	public boolean powered() {
		return powered;
	}

	public double maximumSpeed(boolean highGear) {
		return powered && highGear ? maximumSpeed : RAIL.maximumSpeed;
	}

	public double acceleration(boolean highGear) {
		return powered && highGear ? acceleration : RAIL.acceleration;
	}

	public static RingVehicleVariant byId(int id) {
		return id == POWERED.ordinal() ? POWERED : RAIL;
	}
}
