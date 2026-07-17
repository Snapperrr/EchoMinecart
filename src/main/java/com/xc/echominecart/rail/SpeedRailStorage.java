package com.xc.echominecart.rail;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;

public final class SpeedRailStorage {
	public static final double DEFAULT_SPEED = 1.0D;
	public static final double MAX_ABSOLUTE_SPEED = 256.0D;

	private static final String STATE_ID = "echominecart_speed_rails";
	private static final PersistentState.Type<State> STATE_TYPE = new PersistentState.Type<>(
			State::new,
			State::fromNbt,
			null);

	private SpeedRailStorage() {
	}

	public static double getSpeed(ServerWorld world, BlockPos pos) {
		return state(world).speeds.getOrDefault(pos.asLong(), DEFAULT_SPEED);
	}

	public static double setSpeed(ServerWorld world, BlockPos pos, double speed) {
		double clamped = clampSpeed(speed);
		State state = state(world);
		if (Double.compare(clamped, DEFAULT_SPEED) == 0) {
			state.speeds.remove(pos.asLong());
		} else {
			state.speeds.put(pos.asLong(), clamped);
		}
		state.markDirty();
		return clamped;
	}

	public static void remove(ServerWorld world, BlockPos pos) {
		State state = state(world);
		if (state.speeds.remove(pos.asLong()) != null) {
			state.markDirty();
		}
	}

	public static double clampSpeed(double speed) {
		if (!Double.isFinite(speed)) {
			return DEFAULT_SPEED;
		}
		return Math.max(-MAX_ABSOLUTE_SPEED, Math.min(MAX_ABSOLUTE_SPEED, speed));
	}

	private static State state(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(STATE_TYPE, STATE_ID);
	}

	private static final class State extends PersistentState {
		private final Map<Long, Double> speeds = new HashMap<>();

		private static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			State state = new State();
			NbtList rails = nbt.getList("Rails", NbtElement.COMPOUND_TYPE);
			for (int index = 0; index < rails.size(); index++) {
				NbtCompound rail = rails.getCompound(index);
				double speed = clampSpeed(rail.getDouble("Speed"));
				if (Double.compare(speed, DEFAULT_SPEED) != 0) {
					state.speeds.put(rail.getLong("Pos"), speed);
				}
			}
			return state;
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			NbtList rails = new NbtList();
			for (Map.Entry<Long, Double> entry : speeds.entrySet()) {
				NbtCompound rail = new NbtCompound();
				rail.putLong("Pos", entry.getKey());
				rail.putDouble("Speed", entry.getValue());
				rails.add(rail);
			}
			nbt.put("Rails", rails);
			return nbt;
		}
	}
}
