package com.xc.echominecart.ringvehicle;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Central balance and presentation table for every spider-vehicle ammunition type. */
public enum SpiderAmmoType {
	COPPER(Items.COPPER_INGOT, 6.0F, 3.10D, 0.10D, 0xE77C56, 0.0F, ProjectileModel.LASER),
	IRON(Items.IRON_INGOT, 8.0F, 3.20D, 0.12D, 0xDDE4E6, 0.0F, ProjectileModel.LASER),
	GOLD(Items.GOLD_INGOT, 10.0F, 3.30D, 0.14D, 0xFFD83D, 0.0F, ProjectileModel.LASER),
	AMETHYST(Items.AMETHYST_SHARD, 12.0F, 3.35D, 0.16D, 0xC984F4, 0.0F, ProjectileModel.LASER),
	EMERALD(Items.EMERALD, 15.0F, 3.45D, 0.18D, 0x45E886, 0.0F, ProjectileModel.LASER),
	DIAMOND(Items.DIAMOND, 18.0F, 3.55D, 0.21D, 0x60F5ED, 0.0F, ProjectileModel.LASER),
	END_CRYSTAL(Items.END_CRYSTAL, 30.0F, 1.85D, 0.38D, 0xF2A6FF, 3.0F, ProjectileModel.ITEM),
	RESPAWN_ANCHOR(Items.RESPAWN_ANCHOR, 42.0F, 1.55D, 0.52D, 0x8C5CFF, 4.5F, ProjectileModel.BLOCK);

	private final Item item;
	private final float damage;
	private final double speed;
	private final double recoil;
	private final int color;
	private final float explosionPower;
	private final ProjectileModel projectileModel;

	SpiderAmmoType(Item item, float damage, double speed, double recoil, int color,
			float explosionPower, ProjectileModel projectileModel) {
		this.item = item;
		this.damage = damage;
		this.speed = speed;
		this.recoil = recoil;
		this.color = color;
		this.explosionPower = explosionPower;
		this.projectileModel = projectileModel;
	}

	public Item item() {
		return item;
	}

	public float damage() {
		return damage;
	}

	public double speed() {
		return speed;
	}

	public double recoil() {
		return recoil;
	}

	public int color() {
		return color;
	}

	public float explosionPower() {
		return explosionPower;
	}

	public ProjectileModel projectileModel() {
		return projectileModel;
	}

	public boolean explosive() {
		return explosionPower > 0.0F;
	}

	public static SpiderAmmoType fromStack(ItemStack stack) {
		for (SpiderAmmoType type : values()) {
			if (stack.isOf(type.item)) {
				return type;
			}
		}
		return null;
	}

	public static SpiderAmmoType byId(int id) {
		SpiderAmmoType[] values = values();
		return id >= 0 && id < values.length ? values[id] : IRON;
	}

	public static boolean isAmmo(ItemStack stack) {
		return fromStack(stack) != null;
	}

	public enum ProjectileModel {
		LASER,
		ITEM,
		BLOCK
	}
}
