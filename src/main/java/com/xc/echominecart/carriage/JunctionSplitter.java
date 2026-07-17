package com.xc.echominecart.carriage;

import com.xc.echominecart.rail.OmniRailBlock;
import com.xc.echominecart.rail.RailPhysics;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 分叉分身：车厢组的任意轮组驶过多分叉铁轨时，向每个未被占用的分支
 * 派出一辆隐形的"分支探针车"。探针车是真实矿车，独立沿自己的分支行驶
 * （地面走原版物理、墙面走 RailPhysics），走过的轨迹格计入车厢覆盖格——
 * 车体因此像树枝一样沿所有分支持续拉伸，每条分支路径都有真实运动。
 *
 * 探针车生命周期：
 * - 轨迹超过上限、停滞、脱轨、与组内其他车厢汇合时被吸收（车体缩短）；
 * - 探针车经过更深的分叉还会继续分裂（受组内探针总数上限约束）；
 * - 探针车带命令标签，注册表丢失（如重启）后作为孤儿被清理。
 */
public final class JunctionSplitter {
	public static final String RUNNER_TAG = "echominecart_branch_runner";
	// Kept behind a switch so the experimental branch-stretch implementation can be revisited later.
	private static final boolean ENABLED = false;
	private static final int MAX_RUNNERS_PER_GROUP = 6;
	private static final int MAX_TRAIL_CELLS = 24;
	private static final int STILL_TICKS_TO_ABSORB = 30;
	private static final int JUNCTION_COOLDOWN_TICKS = 30;
	private static final double MIN_SPLIT_SPEED = 0.05D;
	private static final double RUNNER_LAUNCH_SPEED = 0.18D;

	private static final Map<UUID, RunnerData> RUNNERS = new HashMap<>();
	private static final Map<Long, Integer> JUNCTION_COOLDOWNS = new HashMap<>();

	private JunctionSplitter() {
	}

	public static boolean isEnabled() {
		return ENABLED;
	}

	public static boolean isRunner(UUID cartId) {
		return ENABLED && RUNNERS.containsKey(cartId);
	}

	/** 清理已消失矿车的探针注册项（如被 /kill 或区块事故移除的探针）。 */
	public static void forgetCarts(Set<UUID> liveCartIds) {
		if (!ENABLED) {
			RUNNERS.clear();
			JUNCTION_COOLDOWNS.clear();
			return;
		}
		RUNNERS.keySet().retainAll(liveCartIds);
	}

	/** 探针 → 母车 的连接关系，供车厢分组的并查集使用。 */
	public static Map<UUID, UUID> links() {
		if (!ENABLED) {
			return Map.of();
		}
		Map<UUID, UUID> links = new HashMap<>();
		for (Map.Entry<UUID, RunnerData> entry : RUNNERS.entrySet()) {
			links.put(entry.getKey(), entry.getValue().sourceCart);
		}
		return links;
	}

	/** 该组所有探针车走过的轨迹格（树枝形拉伸的来源）。 */
	public static Set<BlockPos> trailCells(Set<UUID> groupCartIds) {
		if (!ENABLED) {
			return Set.of();
		}
		Set<BlockPos> cells = new LinkedHashSet<>();
		for (Map.Entry<UUID, RunnerData> entry : RUNNERS.entrySet()) {
			if (groupCartIds.contains(entry.getKey())) {
				cells.addAll(entry.getValue().trail);
			}
		}
		return cells;
	}

	public static void tickGroup(ServerWorld world, Set<UUID> groupCartIds, List<AbstractMinecartEntity> carts, int serverTick) {
		if (!ENABLED) {
			return;
		}
		JUNCTION_COOLDOWNS.values().removeIf(expiry -> expiry < serverTick);
		int groupRunners = countGroupRunners(groupCartIds);
		for (AbstractMinecartEntity cart : carts) {
			RunnerData data = RUNNERS.get(cart.getUuid());
			if (data != null) {
				if (tickRunnerLifecycle(world, cart, data, carts)) {
					groupRunners--;
					continue;
				}
			}
			if (CarriageManager.isSuppressedModule(cart)) {
				continue;
			}
			groupRunners += trySplit(world, cart, groupRunners, serverTick);
		}
	}

	/** 清理注册表里没有的带标签矿车（重启后遗留的孤儿探针）。 */
	public static void purgeOrphans(ServerWorld world) {
		for (Entity entity : world.iterateEntities()) {
			if (entity != null
					&& entity instanceof AbstractMinecartEntity
					&& entity.getCommandTags().contains(RUNNER_TAG)
					&& (!ENABLED || !RUNNERS.containsKey(entity.getUuid()))) {
				entity.discard();
			}
		}
	}

	// -------------------------------------------------------------- split

	private static int trySplit(ServerWorld world, AbstractMinecartEntity cart, int groupRunners, int serverTick) {
		if (groupRunners >= MAX_RUNNERS_PER_GROUP) {
			return 0;
		}
		Vec3d velocity = cart.getVelocity();
		if (velocity.length() < MIN_SPLIT_SPEED) {
			return 0;
		}
		var contact = RailPhysics.findContact(world, cart).orElse(null);
		if (contact == null) {
			return 0;
		}
		List<Direction> connections = OmniRailBlock.connections(contact.state());
		if (connections.size() <= 2) {
			return 0;
		}
		long junctionKey = contact.pos().asLong();
		if (JUNCTION_COOLDOWNS.containsKey(junctionKey)) {
			return 0;
		}
		Direction travel = dominantDirection(velocity, connections);
		if (travel == null) {
			return 0;
		}
		int spawned = 0;
		for (Direction branch : connections) {
			if (branch == travel || branch == travel.getOpposite()) {
				continue;
			}
			if (groupRunners + spawned >= MAX_RUNNERS_PER_GROUP) {
				break;
			}
			if (spawnRunner(world, cart, contact.pos(), contact.face(), branch, velocity.length())) {
				spawned++;
			}
		}
		if (spawned > 0) {
			JUNCTION_COOLDOWNS.put(junctionKey, serverTick + JUNCTION_COOLDOWN_TICKS);
		}
		return spawned;
	}

	private static boolean spawnRunner(ServerWorld world, AbstractMinecartEntity source, BlockPos junction, Direction face, Direction branch, double speed) {
		Vec3d branchVec = Vec3d.of(branch.getVector());
		Vec3d spawnPos = RailPhysics.surfacePoint(junction, face).add(branchVec.multiply(0.3D));
		AbstractMinecartEntity runner = AbstractMinecartEntity.create(
				world, spawnPos.x, spawnPos.y, spawnPos.z, AbstractMinecartEntity.Type.RIDEABLE, ItemStack.EMPTY, null);
		runner.setInvisible(true);
		runner.setSilent(true);
		runner.addCommandTag(RUNNER_TAG);
		runner.setVelocity(branchVec.multiply(Math.max(speed, RUNNER_LAUNCH_SPEED)));
		runner.velocityModified = true;
		if (!world.spawnEntity(runner)) {
			return false;
		}
		RunnerData data = new RunnerData(source.getUuid());
		data.trail.add(junction);
		RUNNERS.put(runner.getUuid(), data);
		return true;
	}

	// ----------------------------------------------------------- lifecycle

	/** 返回 true 表示探针已被吸收销毁。 */
	private static boolean tickRunnerLifecycle(ServerWorld world, AbstractMinecartEntity runner, RunnerData data, List<AbstractMinecartEntity> groupCarts) {
		BlockPos cell = railCellOf(world, runner);
		if (cell == null) {
			return absorb(runner);
		}
		data.trail.add(cell);
		if (data.trail.size() > MAX_TRAIL_CELLS) {
			return absorb(runner);
		}
		data.stillTicks = runner.getVelocity().length() < 0.02D ? data.stillTicks + 1 : 0;
		if (data.stillTicks >= STILL_TICKS_TO_ABSORB) {
			return absorb(runner);
		}
		if (data.trail.size() >= 3 && convergedWithGroup(runner, groupCarts)) {
			return absorb(runner);
		}
		return false;
	}

	private static boolean absorb(AbstractMinecartEntity runner) {
		RUNNERS.remove(runner.getUuid());
		runner.discard();
		return true;
	}

	private static boolean convergedWithGroup(AbstractMinecartEntity runner, List<AbstractMinecartEntity> groupCarts) {
		for (AbstractMinecartEntity other : groupCarts) {
			if (other == runner || RUNNERS.containsKey(other.getUuid())) {
				continue;
			}
			if (runner.squaredDistanceTo(other) < 1.44D) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------- helpers

	private static int countGroupRunners(Set<UUID> groupCartIds) {
		int count = 0;
		for (UUID runnerId : RUNNERS.keySet()) {
			if (groupCartIds.contains(runnerId)) {
				count++;
			}
		}
		return count;
	}

	private static Direction dominantDirection(Vec3d velocity, List<Direction> connections) {
		Direction best = null;
		double bestDot = 0.0D;
		for (Direction connection : connections) {
			double dot = velocity.dotProduct(Vec3d.of(connection.getVector()));
			if (dot > bestDot) {
				bestDot = dot;
				best = connection;
			}
		}
		return best;
	}

	private static BlockPos railCellOf(ServerWorld world, AbstractMinecartEntity runner) {
		BlockPos base = runner.getBlockPos();
		for (BlockPos pos : new BlockPos[]{base, base.down(), base.up()}) {
			if (AbstractRailBlock.isRail(world.getBlockState(pos))) {
				return pos;
			}
		}
		return null;
	}

	private static final class RunnerData {
		private final UUID sourceCart;
		private final LinkedHashSet<BlockPos> trail = new LinkedHashSet<>();
		private int stillTicks;

		private RunnerData(UUID sourceCart) {
			this.sourceCart = sourceCart;
		}
	}
}
