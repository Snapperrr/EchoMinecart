package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import com.xc.echominecart.network.SpiderWeaponFiredPayload;
import com.xc.echominecart.screen.RingVehicleScreenHandler;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-authoritative entity for both ring-vehicle movement modes.
 *
 * <p>Terrain mode samples support around the ring and moves in the tangent plane of the selected
 * contact normal. Disc mode turns the same model into a rotorcraft whose lift is derived from the
 * signed inner-cart and outer-ring angular velocities. Physics, abilities, mining, damage, and
 * inventory mutation run on the logical server; tracked data exposes the compact state required by
 * client rendering, sound, camera placement, and HUD code.</p>
 *
 * <p>The entity anchor is the bottom-center of its collision volume. Surface normals point away
 * from supporting terrain, and the tracked forward vector is always tangent to that surface.</p>
 */
public final class RingVehicleEntity extends Entity {
	// Clutch phases form a server-side state machine; clients render the tracked phase and angles.
	public static final int CLUTCH_IDLE = 0;
	public static final int CLUTCH_SPINNING = 1;
	public static final int CLUTCH_ALIGNING = 2;
	public static final int CLUTCH_ENGAGED = 3;
	public static final int SPIDER_EXPLORATION_AUTO = 0;
	public static final int SPIDER_EXPLORATION_UP = 1;
	public static final int SPIDER_EXPLORATION_DOWN = 2;
	public static final int MAX_RING_LEVEL = 1;
	public static final float SIZE = 3.0F;
	public static final float EXPANDED_SIZE = 5.0F;
	public static final double INNER_CART_ORBIT_RADIUS = 1.15D;
	public static final int MAX_DISC_EXTRA_MINECARTS = 7;
	public static final double COLLISION_HALF_THICKNESS = 0.50D;
	public static final double COLLISION_OUTER_RADIUS = 1.5D;
	public static final double COLLISION_CORNER_RADIUS = 0.62D;
	private static final double CONTACT_SAMPLE_SPACING = 1.05D;
	private static final double EXPANDED_COLLISION_EDGE_INSET = 0.14D;
	private static final double PASSENGER_RENDER_SEAT_DEPTH = 0.50D;
	private static final double MIN_INPUT = 0.02D;
	private static final double BODY_TURN_RADIANS = Math.toRadians(3.0D);
	private static final double AIR_TURN_RADIANS = Math.toRadians(0.9D);
	private static final double MAX_STEERING_INPUT = 3.0D;
	private static final int MIN_SUPPORT_SAMPLES = 2;
	private static final int TRANSITION_SUPPORT_SAMPLES = 3;
	private static final int HIGH_WALL_SUPPORT_SAMPLES = 6;
	private static final int CONTACT_GRACE_TICKS = 5;
	private static final int CONTACT_SWITCH_TICKS = 3;
	private static final int CONTACT_SWITCH_COOLDOWN = 10;
	private static final int DASH_COOLDOWN_TICKS = 30;
	private static final int FLUID_JUMP_GRACE_TICKS = 10;
	private static final double HARD_LANDING_SPEED = 0.55D;
	private static final double MIN_IMPACT_ATTACK_SPEED = 0.18D;
	private static final double MAX_IMPACT_DAMAGE = 500.0D;
	private static final double IMPACT_DAMAGE_EXPONENT = 2.35D;
	private static final int IMPACT_ATTACK_COOLDOWN_TICKS = 10;
	private static final double CLUTCH_ROLLING_DRAG = 0.985D;
	private static final float MIN_ALIGNMENT_SPEED_DEGREES = 12.0F;
	private static final float CLUTCH_IDLE_ANGULAR_DRAG = 0.985F;
	private static final double CLUTCH_TRANSFER_EFFICIENCY = 1.85D;
	private static final double DISC_COLLISION_HEIGHT = 0.90D;
	private static final double DISC_RENDER_CENTER_HEIGHT = 0.48D;
	private static final double DISC_CART_VERTICAL_OFFSET = 0.10D;
	private static final double DISC_PASSENGER_SEAT_DEPTH = 0.40D;
	public static final double SPIDER_BODY_LIFT = 0.90D;
	private static final double SPIDER_SEAT_OFFSET = -0.57D;
	private static final float SPIDER_SUSPENSION_REST_OFFSET = -0.075F;
	public static final int SPIDER_LEG_COUNT = 8;
	public static final float SPIDER_LEG_MAX_HEALTH = 40.0F;
	public static final float SPIDER_BODY_MAX_HEALTH = 120.0F;
	private static final int SPIDER_FULL_LEG_MASK = (1 << SPIDER_LEG_COUNT) - 1;
	private static final int SPIDER_WEAPON_RECOIL_TICKS = 8;
	private static final int SPIDER_WEAPON_FIRE_COOLDOWN_TICKS = 5;
	private static final int[] SPIDER_INSTALL_ORDER = {0, 4, 2, 6, 1, 5, 3, 7};
	private static final double SPIDER_LEG_REACH_RATIO = 5.10D;
	private static final double SPIDER_LEG_ELASTIC_EXTENSION = 1.15D;
	public static final double SPIDER_LEG_ROOT_RADIUS_RATIO = 1.16D;
	private static final double SPIDER_STRIDE_RATIO = 0.72D;
	private static final double DISC_LIFT_THRESHOLD = 0.62D;
	private static final double DISC_STALL_THRESHOLD = 0.56D;
	private static final double MOMENTUM_STORAGE_MIN_RATE = 0.0035D;
	private static final double MOMENTUM_STORAGE_MAX_RATE = 0.014D;
	private static final double MOMENTUM_STORAGE_DRAIN_RATE = 1.0D / 60.0D;
	private static final double MOMENTUM_BOOST_BASE_ASCENT = 0.95D;
	private static final double MOMENTUM_BOOST_MAX_ASCENT = 1.80D;
	private static final double DISC_BASE_HORIZONTAL_SPEED = 0.48D;
	private static final double DISC_BASE_ASCENT_SPEED = 0.44D;
	private static final double DISC_BASE_DESCENT_SPEED = 0.38D;
	private static final double DISC_WATER_DRAFT = 0.20D;
	private static final double DISC_EXTRA_MINECART_POWER = 0.30D;
	private static final double DISC_EMERGENCY_LIFT_BASE_SPEED = 0.24D;
	private static final double DISC_EMERGENCY_LIFT_BONUS_SPEED = 0.72D;
	private static final double DISC_SMASH_MIN_SPEED = 0.65D;
	private static final double DISC_SMASH_MAX_SPEED = 2.40D;
	private static final float DISC_SMASH_FULL_CHARGE = 0.98F;
	private static final double MINING_REACH_BEYOND_RING = 5.0D;
	private static final double EXPANDED_MINING_REACH_BONUS = 2.0D;
	private static final float MANUAL_MINING_SPEED_MULTIPLIER = 5.0F;
	private static final int MAX_MINING_LAYERS_PER_TICK = 2;
	/*
	 * Spider locomotion is foot driven. These values describe the same three-part leg chain
	 * used by the renderer: the body is deliberately shorter than the total leg reach so the
	 * vehicle is carried by its planted feet instead of being a rolling body with decorations.
	 */
	private static final double SPIDER_LEG_TRIGGER_DISTANCE = 0.82D;
	private static final double SPIDER_LEG_COMFORT_DISTANCE = 1.72D;
	private static final double SPIDER_LEG_TRIGGER_STRETCH = 0.78D;
	private static final double SPIDER_LEG_COMFORT_STRETCH = 0.93D;
	private static final int SPIDER_ADJACENT_STEP_COOLDOWN_TICKS = 4;
	private static final double SPIDER_SUPPORT_POLYGON_LEEWAY_RATIO = 0.14D;
	private static final int SPIDER_JUMP_DASH_WINDOW_TICKS = 16;
	private static final int SPIDER_WALL_CAPTURE_TICKS = 30;
	private static final double SPIDER_WALL_CAPTURE_TILT_RADIANS = Math.toRadians(45.0D);
	private static final double SPIDER_PROBE_TARGET_HYSTERESIS = 0.52D;
	private static final double SPIDER_PROBE_HEIGHT_HYSTERESIS = 0.20D;
	private static final double SPIDER_STALE_SUPPORT_DOT = 0.55D;
	private static final double SPIDER_LEG_LOOK_AHEAD = 1.30D;
	private static final double SPIDER_LEG_LIFT = 0.52D;
	private static final double SPIDER_LEG_MOVE_SPEED = 0.58D;
	private static final double SPIDER_MAX_STEP_PROGRESS_PER_TICK = 0.40D;
	private static final double[] SPIDER_FOOTHOLD_RADIAL_SCALES = {0.74D, 0.50D, 0.28D};
	private static final double SPIDER_CLIFF_PROBE_DEPTH_RATIO = 0.92D;
	private static final double SPIDER_CLIFF_REACH_RATIO = 1.12D;
	private static final double SPIDER_LEG_REVERSE_LIMIT = 0.18D;
	private static final double SPIDER_TURN_RATE_SCALE = 0.68D;
	private static final double SPIDER_MAX_TURN_RADIANS = Math.toRadians(4.0D);
	private static final double SPIDER_TURN_FOOT_LEAD_TICKS = 3.0D;
	private static final double SPIDER_TURN_STEP_PROGRESS = 0.26D;
	private static final double SPIDER_BODY_TRACTION = 0.72D;
	private static final double SPIDER_LOW_SPEED_SCALE = 1.74D;
	private static final double SPIDER_HIGH_SPEED_SCALE = 2.46D;
	private static final double SPIDER_LOW_MAX_SPEED = 1.62D;
	private static final double SPIDER_HIGH_MAX_SPEED = 2.46D;
	private static final double SPIDER_BODY_HEIGHT_LERP = 0.42D;
	private static final double SPIDER_WALL_TRACTION = 0.82D;
	private static final int SPIDER_WALL_MIN_SUPPORT = 2;
	private static final int SPIDER_SURFACE_TRANSITION_TICKS = 22;
	private static final double SPIDER_FALL_SPEED = 0.08D;
	private static final double SPIDER_MAX_FALL_SPEED = 2.5D;
	private static final double SPIDER_WATER_FOOT_DEPTH = 0.02D;
	private static final int SPIDER_LANDING_RECOVERY_TICKS = 8;
	private static final int SPIDER_SURFACE_GRACE_TICKS = 4;
	private static final float SPIDER_DEPLOY_SPEED = 0.055F;
	private static final int SPIDER_LEG_RETRACT_DURATION_TICKS = 12;
	private static final double SPIDER_BODY_NORMAL_STEP = Math.toRadians(8.0D);
	private static final double SPIDER_TRANSITION_NORMAL_STEP = Math.toRadians(5.0D);
	// Tracked values are the network-visible projection of the larger transient state below.
	private static final TrackedData<Integer> VARIANT = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> RING_LEVEL = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Boolean> LAVA_PROOF = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> CHEST_ATTACHED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> HIGH_GEAR = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> CLUTCH_INSTALLED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> MINING_MODE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> FLOATING = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> DISC_MODE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> SPIDER_MODE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> SPIDER_AWAKE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> SPIDER_SLEEP_TRANSITION = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> SPIDER_LEG_MASK = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SPIDER_PENDING_LEG = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SPIDER_RETRACTING_LEG = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SPIDER_LEG_RETRACT_TICKS = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> SPIDER_DEPLOY_PROGRESS = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> SPIDER_GAIT_PHASE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> SPIDER_BODY_COMPRESSION = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Vector3f> SPIDER_BODY_NORMAL = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	private static final TrackedData<Integer> SPIDER_EXPLORATION_MODE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SPIDER_RECOIL_TICKS = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> SPIDER_RECOIL_STRENGTH = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> DISC_EXTRA_MINECARTS = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> FLIGHT_ROTOR_SPEED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Boolean> DISC_FLIGHT_ACTIVE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> MOMENTUM_STORAGE_ENABLED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Integer> JUMP_MODULE_STRENGTH = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> DASH_MODULE_STRENGTH = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> SMASH_MODULE_TYPE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Integer> CLUTCH_PHASE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> INNER_CART_ANGLE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> INNER_CART_ANGULAR_SPEED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Integer> LAUNCH_EVENT = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> LAUNCH_STRENGTH = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Vector3f> CONTACT_NORMAL = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	private static final TrackedData<Vector3f> FORWARD = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
	@SuppressWarnings("unchecked")
	private static final TrackedData<Vector3f>[] SPIDER_FOOT_OFFSETS = new TrackedData[SPIDER_LEG_COUNT];

	static {
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SPIDER_FOOT_OFFSETS[index] = DataTracker.registerData(
					RingVehicleEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
		}
	}

	// Authoritative gameplay state. These fields are never used as client prediction inputs.
	private final RingVehicleInventory inventory = new RingVehicleInventory(this);
	private final Map<Long, Float> miningProgress = new HashMap<>();
	private final Map<Integer, Integer> impactAttackCooldowns = new HashMap<>();
	private float accumulatedDamage;
	private final float[] spiderLegHealth = new float[SPIDER_LEG_COUNT];
	private float spiderBodyHealth = SPIDER_BODY_MAX_HEALTH;
	private int spiderWeaponFireCooldown;
	private int transitionTicks;
	private int movementSoundTicker;
	private int dashCooldown;
	private int dashTicks;
	private double dashForce;
	private double dashSpeedLimit;
	private Vec3d dashDirection = Vec3d.ZERO;
	private float pendingJumpCharge;
	private boolean jumpQueued;
	private boolean smashQueued;
	private float activeSmashCharge;
	private boolean abilityAirborne;
	private boolean smashing;
	private int fluidJumpGraceTicks;
	private int landingBouncesRemaining;
	private double landingBounceVelocity;
	// Client interpolation snapshots; integrated-server ticks update them through the same path.
	private float previousVisualRingAngle;
	private float visualRingAngle;
	private float previousFloatingVisualBlend;
	private float floatingVisualBlend;
	private float previousTurnVisualLean;
	private float turnVisualLean;
	private float previousVisualBodyYaw;
	private float visualBodyYaw;
	private float lastVisualYaw;
	private boolean visualYawInitialized;
	private float previousVisualInnerCartAngle;
	private float visualInnerCartAngle;
	private float previousDiscVisualBlend;
	private float discVisualBlend;
	private float previousVisualSpiderRecoil;
	private float visualSpiderRecoil;
	private float visualSpiderRecoilVelocity;
	// Last control packet. tick() ignores it after a short freshness window.
	private float controlInput;
	private float controlSteering;
	private boolean controlClutchHeld;
	private int lastControlAge = Integer.MIN_VALUE;
	private boolean controlMiningHeld;
	private Vec3d controlMiningDirection = Vec3d.ZERO;
	private int lastMiningControlAge = Integer.MIN_VALUE;
	private ItemStack pendingMigrationDrop = ItemStack.EMPTY;
	private float storedLaunchAngularSpeed;
	private int clutchEngagementDelay;
	private int launchMomentumTicks;
	private int wallHeadingLockTicks;
	private int contactMissingTicks;
	private int contactSwitchCooldown;
	private Vec3d pendingContactNormal = Vec3d.ZERO;
	private int pendingContactTicks;
	private int fluidGraceTicks;
	private double fluidSurfaceY = Double.NaN;
	private boolean discLiftSoundLatched;
	private boolean discSupportedLastTick;
	private int discLandingSoundCooldown;
	private float discClutchRotorStartSpeed;
	private float momentumStorageCharge;
	private boolean momentumReleaseActive;
	private int discEmergencyLiftTicks;
	private double discEmergencyLiftSpeed;
	private boolean discSmashActive;
	private boolean discSmashFullCharge;
	private int discSmashTicks;
	private double discSmashSpeed;
	private final SpiderLegState[] spiderLegs = new SpiderLegState[SPIDER_LEG_COUNT];
	private final Vec3d[] previousVisualSpiderFootOffsets = new Vec3d[SPIDER_LEG_COUNT];
	private final Vec3d[] visualSpiderFootOffsets = new Vec3d[SPIDER_LEG_COUNT];
	private Vec3d previousVisualSpiderBodyNormal = Vec3d.of(Direction.UP.getVector());
	private Vec3d visualSpiderBodyNormal = Vec3d.of(Direction.UP.getVector());
	private float previousSpiderSuspensionOffset = SPIDER_SUSPENSION_REST_OFFSET;
	private float spiderSuspensionOffset = SPIDER_SUSPENSION_REST_OFFSET;
	private float spiderSuspensionVelocity;
	private double previousSpiderSuspensionVerticalSpeed;
	private boolean spiderFeetInitialized;
	private boolean visualSpiderFeetInitialized;
	private float spiderGaitPhase;
	private int spiderGaitHalfSerial;
	private boolean spiderPreviousFirstTripod = true;
	/** Current low-speed wave-gait role: pull, transfer, support, then push. */
	private int spiderGaitRole = -1;
	private int spiderGaitDirectionSign = 1;
	private boolean spiderWasAirborne;
	private int spiderLandingRecoveryTicks;
	private int spiderLandingRecoveryDuration = SPIDER_LANDING_RECOVERY_TICKS;
	private int spiderSurfaceMissingTicks;
	private int spiderSurfaceTransitionTicks;
	private int spiderReverseCliffProbeTicks;
	private int spiderStepSoundCooldown;
	private int spiderCommandStallTicks;
	private int spiderCollisionStallTicks;
	private int spiderRouteMissingTicks;
	private double spiderLastActualTravel;
	private Vec3d spiderExplorationNormalTarget = Vec3d.ZERO;
	private int spiderJumpDashWindowTicks;
	private int spiderWallCaptureTicks;
	private Vec3d spiderWallCaptureNormal = Vec3d.ZERO;
	private double clientTargetX;
	private double clientTargetY;
	private double clientTargetZ;
	private float clientTargetYaw;
	private float clientTargetPitch;
	private int clientLerpTicks;
	private Vec3d lastVisualPosition = Vec3d.ZERO;

	private enum SpiderLegPhase {
		PLANTED,
		SEARCHING,
		SWINGING,
		LANDING
	}

	/** Mutable server-side state for one leg. Foot positions are world-space support points. */
	private static final class SpiderLegState {
		private Vec3d foot = Vec3d.ZERO;
		private Vec3d target = Vec3d.ZERO;
		private Vec3d stepStart = Vec3d.ZERO;
		private Vec3d supportBodyTarget = Vec3d.ZERO;
		private Vec3d stepBodyTarget = Vec3d.ZERO;
		private Vec3d supportNormal = Vec3d.of(Direction.UP.getVector());
		private Vec3d targetNormal = Vec3d.of(Direction.UP.getVector());
		private SpiderLegPhase phase = SpiderLegPhase.SEARCHING;
		private double stepProgress;
		private boolean stepping;
		private boolean turningStep;
		private boolean recoveryStep;
		private boolean grounded;
		private int stableTicks;
		private int lastStepSerial = -1;
	}

	private record SpiderFoothold(Vec3d point, Vec3d normal, Vec3d bodyTarget,
			double radialDistance, double score, boolean pathObstructed) {
	}

	private record SpiderStepCandidate(int legIndex, SpiderFoothold foothold, double urgency,
			boolean emergency, boolean firstGaitGroup) {
	}

	private record SpiderSupportPoint(double x, double y) {
	}

	private record SpiderWallTarget(Vec3d point, Vec3d normal) {
	}

	private record SpiderLegPlan(boolean routeAvailable, int reachableFeet, int plantedFeet) {
	}

	/** Swept projectile result. A non-negative leg index identifies the protected hit region. */
	public record SpiderWeaponHit(int legIndex, Vec3d position, double pathFraction) {
	}

	private record SpiderSurfaceTarget(Vec3d point, double radialDistance, boolean pathObstructed) {
		private SpiderSurfaceTarget(Vec3d point, double radialDistance) {
			this(point, radialDistance, false);
		}
	}

	public RingVehicleEntity(EntityType<? extends RingVehicleEntity> type, World world) {
		super(type, world);
		setNoGravity(true);
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			spiderLegHealth[index] = SPIDER_LEG_MAX_HEALTH;
		}
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		builder.add(VARIANT, RingVehicleVariant.RAIL.ordinal());
		builder.add(RING_LEVEL, 0);
		builder.add(LAVA_PROOF, false);
		builder.add(CHEST_ATTACHED, false);
		builder.add(HIGH_GEAR, false);
		builder.add(CLUTCH_INSTALLED, false);
		builder.add(MINING_MODE, false);
		builder.add(FLOATING, false);
		builder.add(DISC_MODE, false);
		builder.add(SPIDER_MODE, false);
		builder.add(SPIDER_AWAKE, false);
		builder.add(SPIDER_SLEEP_TRANSITION, false);
		builder.add(SPIDER_LEG_MASK, 0);
		builder.add(SPIDER_PENDING_LEG, -1);
		builder.add(SPIDER_RETRACTING_LEG, -1);
		builder.add(SPIDER_LEG_RETRACT_TICKS, 0);
		builder.add(SPIDER_DEPLOY_PROGRESS, 0.0F);
		builder.add(SPIDER_GAIT_PHASE, 0.0F);
		builder.add(SPIDER_BODY_COMPRESSION, 0.0F);
		builder.add(SPIDER_BODY_NORMAL, new Vector3f(0.0F, 1.0F, 0.0F));
		builder.add(SPIDER_EXPLORATION_MODE, SPIDER_EXPLORATION_AUTO);
		builder.add(SPIDER_RECOIL_TICKS, 0);
		builder.add(SPIDER_RECOIL_STRENGTH, 0.0F);
		builder.add(DISC_EXTRA_MINECARTS, 0);
		builder.add(FLIGHT_ROTOR_SPEED, 0.0F);
		builder.add(DISC_FLIGHT_ACTIVE, false);
		builder.add(MOMENTUM_STORAGE_ENABLED, false);
		builder.add(JUMP_MODULE_STRENGTH, 0);
		builder.add(DASH_MODULE_STRENGTH, 0);
		builder.add(SMASH_MODULE_TYPE, 0);
		builder.add(CLUTCH_PHASE, CLUTCH_IDLE);
		builder.add(INNER_CART_ANGLE, 0.0F);
		builder.add(INNER_CART_ANGULAR_SPEED, 0.0F);
		builder.add(LAUNCH_EVENT, 0);
		builder.add(LAUNCH_STRENGTH, 0.0F);
		builder.add(CONTACT_NORMAL, new Vector3f(0.0F, 1.0F, 0.0F));
		builder.add(FORWARD, new Vector3f(0.0F, 0.0F, 1.0F));
		for (TrackedData<Vector3f> footOffset : SPIDER_FOOT_OFFSETS) {
			builder.add(footOffset, new Vector3f(0.0F, -2.4F, 0.0F));
		}
	}

	@Override
	public void tick() {
		super.tick();
		// Clients interpolate tracked state only. Gameplay and collision decisions stay server authoritative.
		if (getWorld().isClient()) {
			tickClientInterpolation();
			updateVisualState();
			updateCollisionBounds();
			return;
		}
		updateVisualState();
		updateCollisionBounds();
		if (!pendingMigrationDrop.isEmpty()) {
			ItemStack overflow = pendingMigrationDrop;
			pendingMigrationDrop = ItemStack.EMPTY;
			dropStack(overflow, 0.25F);
		}
		if (contactSwitchCooldown > 0) {
			contactSwitchCooldown--;
		}
		if (wallHeadingLockTicks > 0) {
			wallHeadingLockTicks--;
		}
		if (dashCooldown > 0) {
			dashCooldown--;
		}
		if (launchMomentumTicks > 0) {
			launchMomentumTicks--;
		}
		if (spiderWeaponFireCooldown > 0) {
			spiderWeaponFireCooldown--;
		}
		int recoilTicks = dataTracker.get(SPIDER_RECOIL_TICKS);
		if (recoilTicks > 0) {
			dataTracker.set(SPIDER_RECOIL_TICKS, recoilTicks - 1);
		}
		if (age % 40 == 0 && !impactAttackCooldowns.isEmpty()) {
			impactAttackCooldowns.entrySet().removeIf(entry -> entry.getValue() <= age);
		}

		if (isLavaProof()) {
			extinguish();
			for (Entity passenger : getPassengerList()) {
				passenger.extinguish();
			}
		}
		if (isInLava() && !isLavaProof()) {
			convertToItem();
			return;
		}

		PlayerEntity controller = getFirstPassenger() instanceof PlayerEntity player ? player : null;
		if (controller == null && isMiningModeEnabled()) {
			setMiningModeEnabled(false);
		}
		for (Entity passenger : getPassengerList()) {
			passenger.fallDistance = 0.0F;
		}
		boolean hasFreshControl = controller != null && lastControlAge >= 0 && age - lastControlAge <= 5;
		double input = controller == null ? 0.0D : hasFreshControl
				? controlInput
				: MathHelper.clamp(controller.forwardSpeed, -1.0F, 1.0F);
		double steering = controller == null ? 0.0D : hasFreshControl
				? controlSteering
				: MathHelper.clamp(controller.sidewaysSpeed, -1.0F, 1.0F);
		if (isSpiderMode()) {
			tickSpiderMovement(controller, hasFreshControl, input, steering);
			return;
		}
		boolean clutchFreewheel = tickClutch(controller, hasFreshControl, input);
		if (isDiscMode()) {
			tickDiscFlight(controller, hasFreshControl, input, steering);
			tickManualMining(controller);
			return;
		}
		double driveInput = clutchFreewheel ? 0.0D : input;
		processQueuedAbilities();
		if (abilityAirborne) {
			tickAbilityAirborne();
			return;
		}
		Vec3d normal = getContactNormal();
		Vec3d forward = getForwardVector();
		boolean floating = updateFluidContact();
		int currentSupport = -1;
		if (floating && contactSwitchCooldown == 0) {
			currentSupport = surfaceScore(center(), normal);
			if (currentSupport < MIN_SUPPORT_SAMPLES) {
				normal = Vec3d.of(Direction.UP.getVector());
				setContactNormal(normal);
				currentSupport = -1;
			}
		}

		if (controller != null && Math.abs(steering) > MIN_INPUT) {
			float candidateYaw = MathHelper.wrapDegrees(getYaw()
					- (float) Math.toDegrees(steering * BODY_TURN_RADIANS));
			Vec3d candidateHeading = bodyHeading(candidateYaw);
			Box candidateBounds = visualBounds(getPos(), candidateHeading);
			if (getWorld().isSpaceEmpty(this, candidateBounds.contract(0.025D))) {
				setYaw(candidateYaw);
				if (wallHeadingLockTicks <= 0) {
					forward = rotateAroundAxis(forward, normal, steering * BODY_TURN_RADIANS).normalize();
					setForwardVector(forward);
				}
			} else {
				Vec3d reaction = (Math.abs(normal.y) < 0.5D ? normal : candidateHeading.negate()).multiply(0.055D);
				if (getWorld().isSpaceEmpty(this, getBoundingBox().offset(reaction).contract(0.025D))) {
					move(MovementType.SELF, reaction);
				}
			}
		}
		if (Math.abs(normal.y) > 0.9D) {
			Vec3d surfaceHeading = projectOntoPlane(getBodyHeading(), normal);
			if (surfaceHeading.lengthSquared() > 0.01D) {
				forward = surfaceHeading.normalize();
				setForwardVector(forward);
			}
		}
		double signedSpeed = getVelocity().dotProduct(forward);
		double surfaceInput = Math.abs(driveInput) > MIN_INPUT
				? driveInput
				: clutchFreewheel && Math.abs(signedSpeed) > MIN_INPUT
						? Math.signum(signedSpeed)
						: dashTicks > 0 ? 1.0D : 0.0D;

		if (currentSupport < 0) {
			currentSupport = surfaceScore(center(), normal);
		}
		Vec3d preContactVelocity = getVelocity();
		if (!floating && normal.y > 0.9D && currentSupport >= MIN_SUPPORT_SAMPLES
				&& preContactVelocity.y < -0.08D
				&& (landingBouncesRemaining > 0 || preContactVelocity.y < -HARD_LANDING_SPEED)
				&& continueLandingBounce(-preContactVelocity.y, preContactVelocity)) {
			updateCollisionBounds();
			for (Entity passenger : getPassengerList()) {
				passenger.fallDistance = 0.0F;
			}
			return;
		}
		Vec3d requestedTravel = Math.abs(surfaceInput) > MIN_INPUT
				? forward.multiply(Math.signum(surfaceInput))
				: Vec3d.ZERO;
		int wallSupportAhead = requestedTravel.lengthSquared() > 0.5D
				? wallSupportAhead(requestedTravel)
				: 0;
		boolean shoreClimb = floating && requestedTravel.lengthSquared() > 0.5D
				&& wallSupportAhead < TRANSITION_SUPPORT_SAMPLES
				&& raisedGroundAhead(requestedTravel);
		boolean terrainStep = !floating && normal.y > 0.9D
				&& requestedTravel.lengthSquared() > 0.5D
				&& (raisedGroundAhead(requestedTravel) || lowRiseAhead(requestedTravel));
		if (floating && currentSupport >= MIN_SUPPORT_SAMPLES) {
			floating = false;
			fluidGraceTicks = 0;
			fluidSurfaceY = Double.NaN;
		}
		if (shoreClimb) {
			floating = false;
			fluidGraceTicks = 0;
			fluidSurfaceY = Double.NaN;
		}
		dataTracker.set(FLOATING, floating);
		if (currentSupport >= MIN_SUPPORT_SAMPLES || floating) {
			contactMissingTicks = 0;
		} else {
			contactMissingTicks++;
		}
		boolean hasContact = floating || shoreClimb || terrainStep || currentSupport >= MIN_SUPPORT_SAMPLES
				|| contactMissingTicks <= CONTACT_GRACE_TICKS;
		if (Math.abs(surfaceInput) > MIN_INPUT && contactSwitchCooldown == 0) {
			Vec3d travel = forward.multiply(Math.signum(surfaceInput));
			Direction travelAxis = dominantDirection(travel);
			Vec3d inwardNormal = Vec3d.of(travelAxis.getOpposite().getVector());
			Vec3d outwardNormal = Vec3d.of(travelAxis.getVector());
			if (!shoreClimb && !terrainStep && hasContact
					&& wallSupportAhead >= TRANSITION_SUPPORT_SAMPLES) {
				boolean leavingFluidForWall = floating && Math.abs(inwardNormal.y) < 0.5D;
				forward = beginSurfaceTransition(normal, inwardNormal, travel, surfaceInput, true, Math.abs(signedSpeed));
				normal = inwardNormal;
				hasContact = true;
				if (leavingFluidForWall) {
					floating = false;
					fluidGraceTicks = 0;
					fluidSurfaceY = Double.NaN;
					dataTracker.set(FLOATING, false);
					signedSpeed = Math.copySign(Math.max(Math.abs(signedSpeed), 0.24D), surfaceInput);
				}
			} else if (!floating && !shoreClimb && !terrainStep && hasContact
					&& leadingEdgeSupport(travel, normal) <= TRANSITION_SUPPORT_SAMPLES
					&& surfaceScore(center().add(travel.multiply(0.82D)), outwardNormal) >= TRANSITION_SUPPORT_SAMPLES) {
				forward = beginSurfaceTransition(normal, outwardNormal, travel, surfaceInput, false, Math.abs(signedSpeed));
				normal = outwardNormal;
				hasContact = true;
			}
		}

		if (!floating && contactMissingTicks > CONTACT_GRACE_TICKS && contactSwitchCooldown == 0) {
			Vec3d recovered = bestNearbyContact(center(), normal);
			if (confirmPendingContact(recovered)) {
				normal = recovered;
				setContactNormal(normal);
				contactMissingTicks = 0;
				contactSwitchCooldown = CONTACT_SWITCH_COOLDOWN;
				hasContact = true;
			}
		} else {
			clearPendingContact();
		}

		Vec3d velocity;
		if (hasContact) {
			double maximumSpeed = getVariant().maximumSpeed(isHighGear());
			double acceleration = getVariant().acceleration(isHighGear());
			double targetSpeed = driveInput * maximumSpeed;
			if (Math.abs(driveInput) <= MIN_INPUT) {
				signedSpeed *= launchMomentumTicks > 0 ? 0.992D
						: clutchFreewheel ? CLUTCH_ROLLING_DRAG : 0.91D;
			} else if (launchMomentumTicks > 0
					&& Math.signum(signedSpeed) == Math.signum(driveInput)
					&& Math.abs(signedSpeed) > maximumSpeed) {
				signedSpeed *= 0.997D;
			} else {
				signedSpeed = approach(signedSpeed, targetSpeed, acceleration);
			}
			velocity = forward.multiply(signedSpeed);
			if (shoreClimb || terrainStep) {
				velocity = velocity.add(0.0D, 0.14D, 0.0D);
			}
			if (floating) {
				double lift = buoyancyVelocity(getVelocity().y);
				velocity = new Vec3d(velocity.x, lift, velocity.z);
			}
			velocity = applyDashForce(velocity);
			setOnGround(normal.y > 0.9D);
			fallDistance = 0.0F;
		} else {
			Vec3d current = getVelocity();
			velocity = new Vec3d(current.x * 0.99D, Math.max(current.y - 0.08D, -2.5D), current.z * 0.99D);
			setOnGround(false);
		}

		setVelocity(velocity);
		velocityDirty = true;
		boolean transition = transitionTicks > 0;
		if (transition) {
			transitionTicks--;
			noClip = true;
		}
		Vec3d positionBeforeMove = getPos();
		move(MovementType.SELF, velocity);
		if (transition) {
			noClip = false;
		}
		if (!transition && hasContact && Math.abs(surfaceInput) > MIN_INPUT
				&& getPos().squaredDistanceTo(positionBeforeMove) < Math.min(0.01D, velocity.lengthSquared() * 0.08D)) {
			Vec3d blockedTravel = forward.multiply(Math.signum(surfaceInput));
			if (!attemptGroundEscape(blockedTravel) && isExpandedRing()) {
				attemptExpandedCornerDeflection(blockedTravel);
			}
		}
		if (!hasContact && !floating && velocity.y < -HARD_LANDING_SPEED
				&& (groundCollision || verticalCollision)) {
			startLandingBounce(-velocity.y, velocity);
		}
		damageEntitiesOnImpact(positionBeforeMove);
		updateCollisionBounds();
		setPitch(0.0F);
		tickMovementEffects(floating);

		tickManualMining(controller);
	}

	/** Advances rotorcraft lift, hover, stall, abilities, collision, and landing as one server tick. */
	private void tickDiscFlight(PlayerEntity controller, boolean hasFreshControl, double throttle, double steering) {
		setContactNormal(Vec3d.of(Direction.UP.getVector()));
		abilityAirborne = false;
		smashQueued = false;
		if (jumpQueued) {
			beginDiscEmergencyLift();
		}
		if (!isDiscFlightActive()) {
			clearDiscAbilityState();
		}
		if (discLandingSoundCooldown > 0) {
			discLandingSoundCooldown--;
		}

		if (controller != null && Math.abs(steering) > MIN_INPUT) {
			setYaw(MathHelper.wrapDegrees(getYaw()
					- (float) Math.toDegrees(steering * BODY_TURN_RADIANS * 0.82D)));
		}
		Vec3d heading = getBodyHeading();
		setForwardVector(heading);
		double powerScale = 1.0D + getDiscExtraMinecarts() * DISC_EXTRA_MINECART_POWER;
		Vec3d current = getVelocity();
		double verticalVelocity;
		boolean clutchRequested = controller != null && hasFreshControl && controlClutchHeld;
		boolean activelySpinning = clutchRequested
				&& hasReinforcedClutch() && getClutchPhase() == CLUTCH_SPINNING;
		double angularRatio = activelySpinning
				? MathHelper.clamp(getInnerCartAngularSpeed() / maximumClutchAngularSpeed(), -1.0D, 1.0D)
				: 0.0D;
		double magnitude = Math.abs(angularRatio);
		if (isMomentumStorageEnabled() && activelySpinning && angularRatio >= DISC_LIFT_THRESHOLD) {
			chargeMomentumStorage(controller, angularRatio);
		}
		if (clutchRequested) {
			double handoff = MathHelper.clamp((magnitude - 0.08D) / 0.78D, 0.0D, 1.0D);
			handoff = handoff * handoff * (3.0D - 2.0D * handoff);
			double targetRotor = discClutchRotorStartSpeed * (1.0D - handoff);
			double handoffRate = Math.max(1.5D, Math.abs(discClutchRotorStartSpeed) * 0.055D);
			setFlightRotorSpeed((float) approach(getFlightRotorSpeed(), targetRotor, handoffRate));
		}
		double hoverRotorReference = Math.max(1.0D, maximumClutchAngularSpeed() * 0.82D);
		double signedRotorRatio = MathHelper.clamp(
				getFlightRotorSpeed() / hoverRotorReference, -1.0D, 1.0D);
		int clutchPhase = getClutchPhase();
		double storedRatio = clutchPhase == CLUTCH_ALIGNING || clutchPhase == CLUTCH_ENGAGED
				? MathHelper.clamp(storedLaunchAngularSpeed / maximumClutchAngularSpeed(), -1.0D, 1.0D)
				: 0.0D;
		// Signed energy lets reverse inner rotation cancel outer-ring lift before it accelerates descent.
		double combinedRotorRatio = MathHelper.clamp(signedRotorRatio
				+ (activelySpinning ? angularRatio : 0.0D) + storedRatio, -1.5D, 1.5D);
		double positiveSupport = Math.max(0.0D, combinedRotorRatio);
		double reversePower = Math.max(0.0D, -combinedRotorRatio);
		boolean producingLift = !isMomentumStorageEnabled() && activelySpinning && angularRatio > 0.0D
				&& combinedRotorRatio >= DISC_LIFT_THRESHOLD;
		if (producingLift) {
			double lift = (magnitude - DISC_LIFT_THRESHOLD) / (1.0D - DISC_LIFT_THRESHOLD);
			double target = (0.06D + DISC_BASE_ASCENT_SPEED * lift) * powerScale;
			verticalVelocity = approach(current.y, target, (0.018D + 0.026D * lift) * powerScale);
		} else if (reversePower > 0.015D) {
			double downwardAcceleration = 0.045D
					+ DISC_BASE_DESCENT_SPEED * 0.16D * reversePower * powerScale;
			verticalVelocity = Math.max(-3.20D * powerScale, current.y - downwardAcceleration);
		} else if (clutchRequested && positiveSupport >= DISC_STALL_THRESHOLD) {
			double target = -(0.055D + 0.030D * (1.0D - positiveSupport));
			verticalVelocity = approach(current.y, target, 0.018D + positiveSupport * 0.010D);
		} else if (!clutchRequested && positiveSupport >= DISC_STALL_THRESHOLD) {
			verticalVelocity = approach(current.y, 0.0D, 0.032D + positiveSupport * 0.018D);
		} else {
			verticalVelocity = Math.max(-3.20D * powerScale, current.y - 0.075D);
		}
		boolean releasingMomentum = momentumReleaseActive && momentumStorageCharge > 0.0001F;
		if (releasingMomentum) {
			double storedStrength = MathHelper.clamp(momentumStorageCharge, 0.0D, 1.0D);
			double boostTarget = MOMENTUM_BOOST_BASE_ASCENT
				+ (MOMENTUM_BOOST_MAX_ASCENT - MOMENTUM_BOOST_BASE_ASCENT) * Math.sqrt(storedStrength);
			double boostScale = Math.sqrt(powerScale);
			verticalVelocity = Math.max(verticalVelocity,
					approach(current.y, boostTarget * boostScale, (0.10D + storedStrength * 0.10D) * boostScale));
			momentumStorageCharge = (float) Math.max(0.0D,
					momentumStorageCharge - MOMENTUM_STORAGE_DRAIN_RATE);
			if (momentumStorageCharge <= 0.0001F) {
				momentumStorageCharge = 0.0F;
				momentumReleaseActive = false;
			}
			setDiscFlightActive(true);
			if (age % 2 == 0 && getWorld() instanceof ServerWorld world) {
				spawnMomentumReleaseJets(world, storedStrength);
			}
		}
		if (producingLift && verticalVelocity > 0.04D) {
			setDiscFlightActive(true);
		}
		if (producingLift && !discLiftSoundLatched) {
			discLiftSoundLatched = true;
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_WHIRL,
					SoundCategory.NEUTRAL, 1.15F + getDiscExtraMinecarts() * 0.05F, 0.82F);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ELYTRA_FLYING,
					SoundCategory.NEUTRAL, 0.72F, 0.88F);
			if (getWorld() instanceof ServerWorld world) {
				spawnDiscTakeoffTurbulence(world, magnitude);
			}
		} else if (!producingLift) {
			discLiftSoundLatched = false;
		}

		double fluidSurface = findFluidSurface();
		boolean nearSupportedFluid = Double.isFinite(fluidSurface)
				&& getY() <= fluidSurface + 0.28D
				&& getY() >= fluidSurface - DISC_COLLISION_HEIGHT - 0.35D;
		double fluidTargetY = nearSupportedFluid ? fluidSurface - DISC_WATER_DRAFT : Double.NaN;
		boolean floatingOnFluid = nearSupportedFluid && !producingLift && !releasingMomentum && !discSmashActive;
		if (floatingOnFluid) {
			fluidSurfaceY = fluidSurface;
			double heightError = MathHelper.clamp(fluidTargetY - getY(), -0.45D, 0.45D);
			verticalVelocity = MathHelper.clamp(current.y * 0.35D + heightError * 0.28D, -0.12D, 0.12D);
		}
		dataTracker.set(FLOATING, floatingOnFluid);

		Vec3d horizontal = new Vec3d(current.x, 0.0D, current.z);
		if (isDiscFlightActive() && !clutchRequested && controller != null && Math.abs(throttle) > MIN_INPUT) {
			double maximumSpeed = DISC_BASE_HORIZONTAL_SPEED * powerScale
					* (getVariant().powered() && isHighGear() ? 1.35D : 1.0D);
			Vec3d target = heading.multiply(throttle * maximumSpeed);
			horizontal = new Vec3d(
					approach(horizontal.x, target.x, 0.032D * powerScale),
					0.0D,
					approach(horizontal.z, target.z, 0.032D * powerScale));
		} else if (clutchRequested) {
			horizontal = horizontal.multiply(0.94D);
		} else {
			horizontal = horizontal.multiply(0.90D);
		}

		if (discEmergencyLiftTicks > 0 && isDiscFlightActive() && !discSmashActive) {
			verticalVelocity = Math.max(verticalVelocity, discEmergencyLiftSpeed);
			discEmergencyLiftTicks--;
			discEmergencyLiftSpeed *= 0.93D;
			if (getWorld() instanceof ServerWorld world && age % 2 == 0) {
				world.spawnParticles(ParticleTypes.CLOUD, getX(), getY() - 0.02D, getZ(),
						3, getRingDiameter() * 0.20D, 0.03D, getRingDiameter() * 0.20D, 0.025D);
			}
		}
		boolean discSmashingThisTick = discSmashActive && isDiscFlightActive();
		if (discSmashingThisTick) {
			horizontal = horizontal.multiply(0.52D);
			verticalVelocity = Math.min(verticalVelocity, -discSmashSpeed);
			double rotorBrake = Math.max(5.0D, maximumClutchAngularSpeed() * 0.10D);
			setFlightRotorSpeed((float) approach(getFlightRotorSpeed(), 0.0D, rotorBrake));
			if (!discSmashFullCharge && --discSmashTicks <= 0) {
				discSmashActive = false;
			}
		}

		Vec3d velocity = applyDashForce(new Vec3d(horizontal.x, verticalVelocity, horizontal.z));
		setVelocity(velocity);
		velocityDirty = true;
		setOnGround(false);
		fallDistance = 0.0F;
		Vec3d positionBeforeMove = getPos();
		move(MovementType.SELF, velocity);
		if (floatingOnFluid && getY() < fluidTargetY) {
			double rise = Math.min(0.16D, fluidTargetY - getY());
			Box raisedBounds = getBoundingBox().offset(0.0D, rise, 0.0D).contract(0.025D);
			if (getWorld().isSpaceEmpty(this, raisedBounds)) {
				setPosition(getX(), getY() + rise, getZ());
			}
		}
		double impactFluidSurface = discSmashingThisTick ? findFluidSurface() : Double.NaN;
		boolean smashFluidLanding = discSmashingThisTick
				&& Double.isFinite(impactFluidSurface)
				&& velocity.y < -0.01D
				&& getY() <= impactFluidSurface - DISC_WATER_DRAFT + 0.14D;
		if (smashFluidLanding) {
			double targetY = impactFluidSurface - DISC_WATER_DRAFT;
			if (getY() < targetY) {
				double rise = targetY - getY();
				Box targetBounds = getBoundingBox().offset(0.0D, rise, 0.0D).contract(0.025D);
				if (getWorld().isSpaceEmpty(this, targetBounds)) {
					setPosition(getX(), targetY, getZ());
					updateCollisionBounds();
				}
			}
			fluidSurfaceY = impactFluidSurface;
			floatingOnFluid = true;
			dataTracker.set(FLOATING, true);
		}
		boolean solidSupport = !getWorld().isSpaceEmpty(this,
				getBoundingBox().contract(0.10D, 0.0D, 0.10D).offset(0.0D, -0.06D, 0.0D));
		boolean solidLanding = solidSupport && velocity.y <= -0.01D;
		boolean supportedNow = solidSupport || floatingOnFluid;
		if (solidSupport) {
			setVelocity(getVelocity().x, 0.0D, getVelocity().z);
		}
		if (supportedNow) {
			double rotorBrake = Math.max(8.0D, maximumClutchAngularSpeed() * 0.18D);
			setFlightRotorSpeed((float) approach(getFlightRotorSpeed(), 0.0D, rotorBrake));
		}
		if (supportedNow) {
			setDiscFlightActive(false);
		}
		if (supportedNow && !discSupportedLastTick && velocity.y < -0.04D && discLandingSoundCooldown <= 0) {
			if (discSmashingThisTick) {
				if (smashFluidLanding) {
					finishFluidSmash(impactFluidSurface);
				} else if (solidLanding) {
					finishSmash();
				}
			} else {
				playDiscLandingEffects(floatingOnFluid, Math.min(1.0D, -velocity.y));
			}
			discLandingSoundCooldown = 10;
		}
		if (supportedNow) {
			clearDiscAbilityState();
			momentumReleaseActive = false;
		}
		discSupportedLastTick = supportedNow;
		setOnGround(solidSupport);
		for (Entity passenger : getPassengerList()) {
			passenger.fallDistance = 0.0F;
		}
		damageEntitiesOnImpact(positionBeforeMove);
		updateCollisionBounds();
		setPitch(0.0F);
		tickMovementEffects(false);

		boolean steadyHover = isDiscFlightActive()
				&& !supportedNow
				&& !clutchRequested
				&& !releasingMomentum
				&& !discSmashingThisTick
				&& positiveSupport >= DISC_STALL_THRESHOLD
				&& Math.abs(getVelocity().y) < 0.22D;
		if (steadyHover && age % 2 == 0 && getWorld() instanceof ServerWorld world) {
			spawnDiscHoverJets(world, positiveSupport);
		}

		if (activelySpinning && age % 4 == 0 && getWorld() instanceof ServerWorld world) {
			if (magnitude >= DISC_LIFT_THRESHOLD) {
				world.spawnParticles(ParticleTypes.CLOUD, getX(), getY() - 0.05D, getZ(),
						2 + getDiscExtraMinecarts() / 2,
						getRingDiameter() * 0.22D, 0.03D, getRingDiameter() * 0.22D, 0.015D);
			}
		}
	}

	private void tickSpiderMovement(PlayerEntity controller, boolean hasFreshControl, double throttle, double steering) {
		if (isSpiderSleepTransition()) {
			tickSpiderSleepTransition();
			return;
		}
		if (!isSpiderAwake()) {
			tickSleepingSpider();
			return;
		}
		if (getSpiderDeployProgress() < 1.0F) {
			tickSpiderDeployment();
			return;
		}
		if (throttle >= -MIN_INPUT) {
			spiderReverseCliffProbeTicks = 0;
		} else if (spiderReverseCliffProbeTicks > 0) {
			spiderReverseCliffProbeTicks--;
		}
		if (spiderStepSoundCooldown > 0) {
			spiderStepSoundCooldown--;
		}
		double turnRadians = 0.0D;
		if (controller != null && Math.abs(steering) > MIN_INPUT) {
			turnRadians = MathHelper.clamp(steering * BODY_TURN_RADIANS * SPIDER_TURN_RATE_SCALE,
					-SPIDER_MAX_TURN_RADIANS, SPIDER_MAX_TURN_RADIANS);
			setYaw(MathHelper.wrapDegrees(getYaw() - (float) Math.toDegrees(turnRadians)));
		}
		Vec3d heading = getBodyHeading();
		Vec3d normal = updateSpiderSurface(heading, throttle);
		Vec3d travelForward = spiderTravelForward(normal, heading);
		setForwardVector(travelForward);
		boolean floating = updateFluidContact();
		if (Math.abs(normal.y) < 0.8D) {
			floating = false;
			fluidGraceTicks = 0;
			fluidSurfaceY = Double.NaN;
		}
		dataTracker.set(FLOATING, floating);

		processQueuedAbilities();
		if (abilityAirborne) {
			spiderCommandStallTicks = 0;
			spiderCollisionStallTicks = 0;
			spiderRouteMissingTicks = 0;
			tickAbilityAirborne();
			if (abilityAirborne) {
				spiderWasAirborne = true;
				updateSpiderAirborneLegs(heading);
			} else if (spiderLandingRecoveryTicks <= 0) {
				beginSpiderLandingRecovery(heading);
			}
			publishAllSpiderFeet();
			return;
		}
		if (spiderWasAirborne) {
			beginSpiderLandingRecovery(heading);
		}

		ensureSpiderLegs(normal, travelForward);
		if (spiderLandingRecoveryTicks > 0) {
			updateSpiderLandingRecovery();
		} else {
			updatePreLateralSpiderFootCycle(normal, travelForward, throttle, turnRadians);
		}
		updatePreLateralSpiderBodyNormal(normal);
		int activeLegCount = getSpiderInstalledLegCount();
		int motionSupportCount = spiderMotionSupportCount();
		int requiredSupportCount = Math.min(3, activeLegCount);
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d displacement;
		if (spiderLandingRecoveryTicks > 0) {
			displacement = Vec3d.ZERO;
		} else if (activeLegCount > 0 && motionSupportCount >= requiredSupportCount) {
			Vec3d supportBodyTarget = spiderMotionBodyTarget();
			Vec3d difference = supportBodyTarget.subtract(bodyCenter);
			double speedScale = spiderCrawlSpeedScale();
			double maximumSpeed = spiderMaximumSpeed();
			double travelLimit = Math.max(0.06D, maximumSpeed * 1.08D);
			Vec3d tangentDifference = projectOntoPlane(difference, normal);
			if (tangentDifference.length() > travelLimit) {
				tangentDifference = tangentDifference.normalize().multiply(travelLimit);
			}
			double normalCorrection = MathHelper.clamp(
					difference.dotProduct(normal) * SPIDER_BODY_HEIGHT_LERP, -0.30D, 0.30D);
			if (Math.abs(throttle) <= MIN_INPUT && tangentDifference.lengthSquared() < 0.006D) {
				tangentDifference = Vec3d.ZERO;
			}
			displacement = tangentDifference.multiply(Math.min(0.94D, SPIDER_BODY_TRACTION * speedScale))
					.add(normal.multiply(normalCorrection));
			if (Math.abs(normal.y) < 0.8D && Math.abs(throttle) > MIN_INPUT) {
				displacement = applySpiderWallTraction(displacement, normal, travelForward, throttle,
						maximumSpeed, bodyCenter);
			}
			if (floating) {
				displacement = new Vec3d(displacement.x, buoyancyVelocity(getVelocity().y), displacement.z);
			}
		} else {
			Vec3d current = getVelocity();
			displacement = floating
					? new Vec3d(current.x * 0.92D, buoyancyVelocity(current.y), current.z * 0.92D)
					: new Vec3d(current.x * 0.90D, Math.max(current.y - SPIDER_FALL_SPEED, -SPIDER_MAX_FALL_SPEED), current.z * 0.90D);
		}
		displacement = applyDashForce(displacement);
		setVelocity(displacement);
		velocityDirty = true;
		Vec3d before = getPos();
		move(MovementType.SELF, displacement);
		publishAllSpiderFeet();

		boolean supported = !floating && activeLegCount > 0
				&& motionSupportCount >= requiredSupportCount;
		if (supported && getVelocity().y < 0.0D) {
			if (normal.y > 0.8D) {
				setVelocity(getVelocity().x, 0.0D, getVelocity().z);
			}
		}
		setOnGround(supported && normal.y > 0.8D);
		dataTracker.set(SPIDER_BODY_COMPRESSION,
				(float) approach(dataTracker.get(SPIDER_BODY_COMPRESSION), 0.0D, 0.12D));
		fallDistance = 0.0F;
		for (Entity passenger : getPassengerList()) {
			passenger.fallDistance = 0.0F;
		}
		damageEntitiesOnImpact(before);
		updateCollisionBounds();
		setPitch(0.0F);
		tickMovementEffects(floating);
		tickManualMining(controller);
	}

	private void tickSleepingSpider() {
		spiderCommandStallTicks = 0;
		spiderCollisionStallTicks = 0;
		spiderRouteMissingTicks = 0;
		setVelocity(Vec3d.ZERO);
		velocityDirty = true;
		setContactNormal(Vec3d.of(Direction.UP.getVector()));
		setForwardVector(getBodyHeading());
		dataTracker.set(FLOATING, false);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS, 0.0F);
		dataTracker.set(SPIDER_BODY_COMPRESSION, 0.0F);
		setSpiderBodyNormal(Vec3d.of(Direction.UP.getVector()));
		spiderFeetInitialized = false;
		int retractTicks = dataTracker.get(SPIDER_LEG_RETRACT_TICKS);
		if (retractTicks > 0) {
			retractTicks--;
			dataTracker.set(SPIDER_LEG_RETRACT_TICKS, retractTicks);
			if (retractTicks == 0) {
				dataTracker.set(SPIDER_RETRACTING_LEG, -1);
			}
		}
		updateCollisionBounds();
	}

	/** Retracts the legs while keeping the visible assembly attached until the body is compact. */
	private void tickSpiderSleepTransition() {
		spiderCommandStallTicks = 0;
		spiderCollisionStallTicks = 0;
		spiderRouteMissingTicks = 0;
		setVelocity(Vec3d.ZERO);
		velocityDirty = true;
		dataTracker.set(FLOATING, false);
		float progress = Math.max(0.0F, getSpiderDeployProgress() - SPIDER_DEPLOY_SPEED * 1.25F);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS, progress);
		Vec3d currentNormal = trackedSpiderBodyNormal();
		setSpiderBodyNormal(stepSpiderBodyNormal(currentNormal,
				Vec3d.of(Direction.UP.getVector()), Math.toRadians(12.0D)));
		if (progress <= 0.0F) {
			dataTracker.set(SPIDER_AWAKE, false);
			dataTracker.set(SPIDER_SLEEP_TRANSITION, false);
			dataTracker.set(SPIDER_DEPLOY_PROGRESS, 0.0F);
			spiderFeetInitialized = false;
			for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
				spiderLegs[index] = null;
			}
			setContactNormal(Vec3d.of(Direction.UP.getVector()));
			setSpiderBodyNormal(Vec3d.of(Direction.UP.getVector()));
			setForwardVector(getBodyHeading());
		}
		updateCollisionBounds();
	}

	private void tickSpiderDeployment() {
		spiderCommandStallTicks = 0;
		spiderCollisionStallTicks = 0;
		spiderRouteMissingTicks = 0;
		float previous = getSpiderDeployProgress();
		float progress = Math.min(1.0F, previous + SPIDER_DEPLOY_SPEED);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS, progress);
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d heading = getBodyHeading();
		setContactNormal(up);
		setSpiderBodyNormal(up);
		setForwardVector(heading);
		setVelocity(Vec3d.ZERO);
		ensureSpiderLegs(up, heading);
		publishAllSpiderFeet();
		updateCollisionBounds();
		if (previous < 0.52F && progress >= 0.52F) {
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_PISTON_EXTEND,
					SoundCategory.NEUTRAL, 0.72F, 0.62F);
		}
		if (progress >= 1.0F) {
			Vec3d bodyCenter = spiderBodyCenter();
			for (SpiderLegState leg : spiderLegs) {
				if (leg != null) {
					leg.supportBodyTarget = bodyCenter;
					leg.stepBodyTarget = bodyCenter;
				}
			}
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_IRON_GOLEM_REPAIR,
					SoundCategory.NEUTRAL, 0.58F, 0.72F);
		}
	}

	private Vec3d updateSpiderSurface(Vec3d heading, double throttle) {
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d normal = getContactNormal();
		if (normal.lengthSquared() < 0.5D) {
			normal = up;
		}
		normal = normal.normalize();
		if (normal.y > 0.8D) {
			normal = up;
			spiderSurfaceMissingTicks = 0;
			if (throttle > MIN_INPUT) {
				Vec3d wallNormal = detectSpiderWallNormal(heading);
				if (wallNormal.lengthSquared() > 0.5D) {
					normal = wallNormal;
					beginSpiderSurfaceChange(normal);
				}
			} else if (throttle < -MIN_INPUT && spiderReverseCliffProbeTicks > 0) {
				Vec3d reverseDirection = heading.negate();
				Vec3d descendingWall = detectSpiderDescendingWallNormal(reverseDirection);
				if (descendingWall.lengthSquared() > 0.5D) {
					normal = descendingWall;
					// Reverse input now maps the chassis forward axis upward, producing downward travel.
					setForwardVector(up);
					beginSpiderSurfaceChange(normal);
				}
			}
		} else if (hasSpiderSurface(normal)) {
			spiderSurfaceMissingTicks = 0;
		} else if (++spiderSurfaceMissingTicks > SPIDER_SURFACE_GRACE_TICKS) {
			Vec3d transitionNormal = findSpiderTransitionNormal(heading, throttle, normal);
			normal = transitionNormal.lengthSquared() > 0.5D ? transitionNormal : up;
			beginSpiderSurfaceChange(normal);
			spiderSurfaceMissingTicks = 0;
		}
		setContactNormal(normal);
		return normal;
	}

	/** Hands an ascending wall gait to the top face before the chassis wedges against the lip. */
	private Vec3d detectSpiderWallCrestNormal(Vec3d wallNormal, Vec3d heading, double throttle) {
		if (Math.abs(wallNormal.y) > 0.35D || Math.abs(throttle) <= MIN_INPUT) {
			return Vec3d.ZERO;
		}
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d commandDirection = spiderTravelForward(wallNormal, heading)
				.multiply(Math.signum(throttle));
		if (commandDirection.y < 0.52D) {
			return Vec3d.ZERO;
		}
		int requiredTopFeet = Math.min(2, getSpiderInstalledLegCount());
		if (requiredTopFeet > 0 && spiderSupportCount(up) >= requiredTopFeet) {
			return up;
		}

		Vec3d inward = projectOntoPlane(heading, up);
		if (inward.lengthSquared() < 0.01D || inward.dotProduct(wallNormal) > -0.25D) {
			inward = wallNormal.negate();
		} else {
			inward = inward.normalize();
		}
		double radius = getRingDiameter() * 0.5D;
		Vec3d bodyCenter = spiderBodyCenter();
		for (double inwardDistance : new double[]{radius * 0.42D, radius * 0.72D, radius + 0.45D}) {
			Vec3d column = bodyCenter.add(inward.multiply(inwardDistance));
			Vec3d start = column.add(up.multiply(radius * 1.35D + 1.0D));
			Vec3d end = column.subtract(up.multiply(radius * 0.48D + 0.55D));
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start, end,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() == HitResult.Type.MISS
					|| Vec3d.of(hit.getSide().getVector()).dotProduct(up) < 0.72D) {
				continue;
			}
			double relativeHeight = hit.getPos().y - bodyCenter.y;
			if (relativeHeight >= -radius * 0.35D && relativeHeight <= radius * 1.08D) {
				return up;
			}
		}
		return Vec3d.ZERO;
	}

	private Vec3d findSpiderTransitionNormal(Vec3d heading, double throttle, Vec3d previousNormal) {
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d previousTravel = spiderTravelForward(previousNormal, heading);
		double inputSign = Math.abs(throttle) > MIN_INPUT ? Math.signum(throttle) : 1.0D;
		Vec3d predictedCenter = bodyCenter.add(previousTravel.multiply(
				inputSign * (getRingDiameter() * 0.34D + 0.65D)));
		Vec3d bestNormal = Vec3d.ZERO;
		double bestScore = Double.POSITIVE_INFINITY;
		for (Direction direction : Direction.values()) {
			Vec3d candidateNormal = Vec3d.of(direction.getVector());
			double continuity = candidateNormal.dotProduct(previousNormal);
			if (continuity < -0.35D) {
				continue;
			}
			Vec3d expectedSurface = predictedCenter.subtract(candidateNormal.multiply(spiderStandHeight()));
			Vec3d surface = findSpiderSurface(expectedSurface, candidateNormal, 0.85D, 1.15D);
			if (surface == null) {
				continue;
			}
			Vec3d candidateCenter = surface.add(candidateNormal.multiply(spiderStandHeight()));
			double placementError = candidateCenter.distanceTo(predictedCenter);
			if (placementError > getRingDiameter() * 0.72D + 1.0D) {
				continue;
			}
			double turnPenalty = (1.0D - continuity) * 0.28D;
			double score = placementError + turnPenalty;
			if (score < bestScore) {
				bestScore = score;
				bestNormal = candidateNormal;
			}
		}
		return bestNormal;
	}

	private Vec3d detectSpiderWallNormal(Vec3d heading) {
		Vec3d bodyCenter = spiderBodyCenter();
		double radius = getRingDiameter() * 0.5D;
		double distance = radius + 1.65D;
		Vec3d detectedNormal = Vec3d.ZERO;
		int matchingHits = 0;
		for (double height : new double[]{-radius * 0.65D, -radius * 0.30D, radius * 0.15D}) {
			Vec3d start = bodyCenter.add(0.0D, height, 0.0D);
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start,
					start.add(heading.multiply(distance)), RaycastContext.ShapeType.COLLIDER,
					RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() == HitResult.Type.MISS) {
				continue;
			}
			Vec3d normal = Vec3d.of(hit.getSide().getVector());
			if (Math.abs(normal.y) < 0.5D && normal.dotProduct(heading) < -0.45D) {
				if (detectedNormal.lengthSquared() < 0.5D || detectedNormal.dotProduct(normal) > 0.95D) {
					detectedNormal = normal;
					matchingHits++;
				}
			}
		}
		return matchingHits >= 2 ? detectedNormal : Vec3d.ZERO;
	}

	/** Detects the near vertical face of a ledge without searching through terrain or underground caves. */
	private Vec3d detectSpiderDescendingWallNormal(Vec3d heading) {
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d bodyCenter = spiderBodyCenter();
		double radius = getRingDiameter() * 0.5D;
		Vec3d frontGround = bodyCenter.add(heading.multiply(radius + 0.85D))
				.subtract(up.multiply(spiderStandHeight()));
		if (findSpiderSurface(frontGround, up, 0.65D, 1.10D) != null) {
			return Vec3d.ZERO;
		}

		Vec3d detected = Vec3d.ZERO;
		int hits = 0;
		for (double drop : new double[]{0.45D, radius * 0.55D, radius * 1.05D}) {
			Vec3d start = bodyCenter.add(heading.multiply(radius + 1.35D))
					.subtract(up.multiply(spiderStandHeight() * 0.55D + drop));
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start,
					start.subtract(heading.multiply(2.10D)), RaycastContext.ShapeType.COLLIDER,
					RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() == HitResult.Type.MISS) {
				continue;
			}
			Vec3d candidate = Vec3d.of(hit.getSide().getVector());
			if (Math.abs(candidate.y) < 0.5D && candidate.dotProduct(heading) > 0.55D) {
				detected = candidate;
				hits++;
			}
		}
		return hits >= 1 ? detected : Vec3d.ZERO;
	}

	private boolean hasSpiderSurface(Vec3d normal) {
		Vec3d sample = spiderBodyCenter().subtract(normal.multiply(spiderStandHeight()));
		return findSpiderSurface(sample, normal, 0.85D, 1.10D) != null;
	}

	private void beginSpiderSurfaceChange(Vec3d newNormal) {
		setContactNormal(newNormal);
		spiderSurfaceTransitionTicks = SPIDER_SURFACE_TRANSITION_TICKS;
		Vec3d bodyCenter = spiderBodyCenter();
		for (SpiderLegState leg : spiderLegs) {
			if (leg != null && leg.grounded && !leg.stepping) {
				leg.supportBodyTarget = bodyCenter;
			}
		}
		spiderGaitPhase = 0.0F;
		spiderGaitHalfSerial++;
		spiderPreviousFirstTripod = true;
		dataTracker.set(SPIDER_GAIT_PHASE, spiderGaitPhase);
	}

	/**
	 * A wall transition keeps the old ground feet planted while newly attached wall feet pull the
	 * body upward. Requiring two wall contacts prevents a single stray raycast from lifting the
	 * whole vehicle, while the explicit tangent force avoids the old deadlock where the body had to
	 * move before another foot could find a useful wall target.
	 */
	private Vec3d applySpiderWallTraction(Vec3d displacement, Vec3d normal, Vec3d travelForward,
			double throttle, double maximumSpeed, Vec3d bodyCenter) {
		int wallSupports = spiderSupportCount(normal);
		int minimumWallSupport = Math.min(SPIDER_WALL_MIN_SUPPORT, getSpiderInstalledLegCount());
		if (minimumWallSupport <= 0 || wallSupports < minimumWallSupport) {
			return displacement;
		}
		double supportBlend = MathHelper.clamp(
				(wallSupports - minimumWallSupport + 1.0D) / 4.0D, 0.25D, 1.0D);
		double desiredTangentSpeed = Math.signum(throttle) * maximumSpeed
				* SPIDER_WALL_TRACTION * (0.55D + supportBlend * 0.45D);
		double currentTangentSpeed = displacement.dotProduct(travelForward);
		displacement = displacement.add(travelForward.multiply(
				(desiredTangentSpeed - currentTangentSpeed) * (0.48D + supportBlend * 0.24D)));

		Vec3d wallPoint = findSpiderSurface(bodyCenter.subtract(normal.multiply(spiderStandHeight())),
				normal, 0.85D, 1.10D);
		if (wallPoint != null) {
			double standoffError = wallPoint.add(normal.multiply(spiderStandHeight()))
					.subtract(bodyCenter).dotProduct(normal);
			displacement = displacement.add(normal.multiply(MathHelper.clamp(
					standoffError * 0.30D, -0.12D, 0.12D)));
		}
		return displacement;
	}

	/** Breaks support-target cancellation only after the legs have found a valid route forward. */
	private Vec3d applySpiderCommandTraction(Vec3d displacement, Vec3d normal, Vec3d travelForward,
			double throttle, double maximumSpeed, int activeLegCount, boolean hasAdvancingFoothold) {
		if (Math.abs(throttle) <= MIN_INPUT || !hasAdvancingFoothold || activeLegCount <= 0) {
			spiderCommandStallTicks = 0;
			return displacement;
		}
		int requiredSurfaceSupport = Math.min(Math.abs(normal.y) < 0.80D ? 2 : 1, activeLegCount);
		if (spiderSupportCount(normal) < requiredSurfaceSupport) {
			spiderCommandStallTicks = 0;
			return displacement;
		}
		Vec3d commandDirection = travelForward.multiply(Math.signum(throttle));
		double commandedSpeed = displacement.dotProduct(commandDirection);
		if (commandedSpeed >= 0.045D) {
			spiderCommandStallTicks = 0;
			return displacement;
		}
		spiderCommandStallTicks++;
		if (spiderCommandStallTicks <= 3) {
			return displacement;
		}
		double rescueSpeed = Math.min(maximumSpeed * 0.22D, 0.24D + getRingLevel() * 0.08D);
		double response = MathHelper.clamp(0.30D + (spiderCommandStallTicks - 4) * 0.07D,
				0.30D, 0.58D);
		double correction = MathHelper.clamp((rescueSpeed - commandedSpeed) * response,
				0.0D, 0.14D + getRingLevel() * 0.04D);
		return displacement.add(commandDirection.multiply(correction));
	}

	/** Keeps the support-driven velocity continuous while allowing steering to change immediately. */
	private Vec3d smoothSpiderSurfaceMotion(Vec3d desired, Vec3d normal, Vec3d travelForward,
			double throttle) {
		if (Math.abs(throttle) <= MIN_INPUT || dashTicks > 0) {
			return desired;
		}
		Vec3d commandDirection = travelForward.multiply(Math.signum(throttle));
		double previousForwardSpeed = Math.max(0.0D, getVelocity().dotProduct(commandDirection));
		if (previousForwardSpeed < 0.015D) {
			return desired;
		}
		Vec3d desiredTangent = projectOntoPlane(desired, normal);
		double desiredNormal = desired.dotProduct(normal);
		double carry = desiredTangent.dotProduct(commandDirection) < 0.025D ? 0.42D : 0.30D;
		Vec3d smoothedTangent = desiredTangent.multiply(1.0D - carry)
				.add(commandDirection.multiply(previousForwardSpeed * carry));
		double smoothedForward = smoothedTangent.dotProduct(commandDirection);
		if (smoothedForward < 0.0D) {
			smoothedTangent = smoothedTangent.subtract(commandDirection.multiply(smoothedForward));
		}
		return smoothedTangent.add(normal.multiply(desiredNormal));
	}

	/** Uses requested-versus-actual movement to free a supported chassis from block corners. */
	private void recoverSpiderCollisionStall(Vec3d requested, Vec3d actual, Vec3d normal,
			Vec3d travelForward, double throttle, boolean hasAdvancingFoothold,
			boolean dashActiveThisTick) {
		if (Math.abs(throttle) <= MIN_INPUT && !dashActiveThisTick) {
			spiderCollisionStallTicks = 0;
			spiderRouteMissingTicks = 0;
			return;
		}
		Vec3d commandDirection = dashActiveThisTick && dashDirection.lengthSquared() > 0.01D
				? dashDirection.normalize()
				: travelForward.multiply(Math.signum(throttle));
		if (commandDirection.lengthSquared() < 0.01D) {
			return;
		}
		double requestedForward = requested.dotProduct(commandDirection);
		double actualForward = actual.dotProduct(commandDirection);
		boolean movementBlocked = requestedForward >= 0.045D
				&& actualForward < Math.max(0.025D, requestedForward * 0.34D);
		if (!hasAdvancingFoothold) {
			spiderRouteMissingTicks++;
			movementBlocked = movementBlocked || spiderRouteMissingTicks >= 4
					&& hasSpiderRecoverySurface(normal, commandDirection);
		} else {
			spiderRouteMissingTicks = 0;
		}
		if (!movementBlocked) {
			spiderCollisionStallTicks = 0;
			return;
		}
		spiderCollisionStallTicks++;
		int recoveryDelay = dashActiveThisTick ? 1 : hasAdvancingFoothold ? 2 : 4;
		if (spiderCollisionStallTicks < recoveryDelay) {
			return;
		}
		if (attemptSpiderCollisionRecovery(normal, commandDirection)) {
			spiderCollisionStallTicks = 0;
			resetSpiderDirectionalProbePlanning(normal, commandDirection);
			spiderRouteMissingTicks = 0;
		} else if (!hasAdvancingFoothold && spiderRouteMissingTicks >= 8) {
			resetSpiderDirectionalProbePlanning(normal, commandDirection);
			spiderRouteMissingTicks = 0;
		}
	}

	private boolean hasSpiderRecoverySurface(Vec3d normal, Vec3d commandDirection) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d targetCenter = spiderBodyCenter().add(commandDirection.multiply(0.34D));
		Vec3d sameFace = findSpiderSurface(
				targetCenter.subtract(safeNormal.multiply(spiderStandHeight())),
				safeNormal, 1.05D, 1.35D);
		if (sameFace != null) {
			return true;
		}
		return Math.abs(safeNormal.y) < 0.35D && commandDirection.y > 0.52D
				&& detectSpiderWallCrestNormal(safeNormal, getBodyHeading(), 1.0D)
						.lengthSquared() > 0.5D;
	}

	/** Drops stale leading-foot anchors after a verified movement deadlock. */
	private void resetSpiderDirectionalProbePlanning(Vec3d normal, Vec3d commandDirection) {
		Vec3d bodyCenter = spiderBodyCenter();
		double travelSign = commandDirection.dotProduct(spiderTravelForward(normal, getBodyHeading())) >= 0.0D
				? 1.0D
				: -1.0D;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null || leg.stepping
					|| !isSpiderDirectionalProbeLeg(spiderLegRole(index), travelSign)) {
				continue;
			}
			leg.target = leg.foot;
			leg.stepStart = leg.foot;
			leg.supportBodyTarget = bodyCenter;
			leg.stepBodyTarget = bodyCenter;
			leg.stableTicks = 0;
			setSpiderLegPhase(leg, SpiderLegPhase.SEARCHING);
		}
		spiderGaitHalfSerial++;
	}

	private boolean attemptSpiderCollisionRecovery(Vec3d normal, Vec3d commandDirection) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d side = safeNormal.crossProduct(commandDirection);
		if (side.lengthSquared() < 0.01D) {
			return false;
		}
		side = side.normalize();
		double sideOrder = (getId() & 1) == 0 ? 1.0D : -1.0D;
		double[] outwardOffsets = Math.abs(safeNormal.y) > 0.80D
				? new double[]{0.06D, 0.16D, 0.32D, 0.56D, 0.88D}
				: new double[]{0.06D, 0.14D, 0.28D, 0.46D, 0.68D, 0.92D};
		for (double outward : outwardOffsets) {
			for (double lateral : new double[]{0.0D, 0.10D * sideOrder, -0.10D * sideOrder,
					0.20D * sideOrder, -0.20D * sideOrder}) {
				Vec3d offset = commandDirection.multiply(0.075D)
						.add(safeNormal.multiply(outward))
						.add(side.multiply(lateral));
				Box candidate = getBoundingBox().offset(offset).contract(0.025D);
				if (getWorld().isSpaceEmpty(this, candidate)) {
					move(MovementType.SELF, offset);
					return true;
				}
			}
		}
		return false;
	}

	private Vec3d spiderTravelForward(Vec3d normal, Vec3d heading) {
		if (Math.abs(normal.y) < 0.30D) {
			Vec3d previousSurfaceTravel = projectOntoPlane(getForwardVector(), normal);
			Vec3d wallUp = projectOntoPlane(Vec3d.of(Direction.UP.getVector()), normal);
			wallUp = wallUp.lengthSquared() < 0.01D
					? Vec3d.of(Direction.UP.getVector())
					: wallUp.normalize();
			Vec3d verticalTravel = previousSurfaceTravel.lengthSquared() > 0.01D
					&& Math.abs(previousSurfaceTravel.normalize().y) > 0.55D
					? previousSurfaceTravel.normalize()
					: wallUp;
			return verticalTravel;
		}
		Vec3d projected = projectOntoPlane(heading, normal);
		return projected.lengthSquared() < 0.01D
				? new Vec3d(0.0D, 0.0D, 1.0D)
				: projected.normalize();
	}

	private void updateSpiderAirborneLegs(Vec3d heading) {
		Vec3d bodyCenter = spiderBodyCenter();
		double bodyRadius = getRingDiameter() * 0.5D;
		double descendingBlend = MathHelper.clamp(-getVelocity().y / 0.72D, 0.0D, 1.0D);
		double radialReach = bodyRadius * MathHelper.lerp(descendingBlend, 1.12D, 1.48D);
		double downwardReach = bodyRadius * MathHelper.lerp(descendingBlend, 0.12D, 0.38D);
		boolean wallCapture = spiderWallCaptureTicks > 0;
		Vec3d captureDirection = spiderAirborneCaptureDirection(heading);
		if (wallCapture) {
			Vec3d up = Vec3d.of(Direction.UP.getVector());
			Vec3d wallDirection = spiderWallCaptureNormal.lengthSquared() > 0.5D
					? spiderWallCaptureNormal.normalize()
					: captureDirection.negate();
			Vec3d desiredNormal = up.multiply(Math.cos(SPIDER_WALL_CAPTURE_TILT_RADIANS))
					.add(wallDirection.multiply(Math.sin(SPIDER_WALL_CAPTURE_TILT_RADIANS)))
					.normalize();
			setSpiderBodyNormal(stepSpiderBodyNormal(
					trackedSpiderBodyNormal(), desiredNormal, Math.toRadians(10.0D)));
		} else {
			setSpiderBodyNormal(stepSpiderBodyNormal(trackedSpiderBodyNormal(),
					Vec3d.of(Direction.UP.getVector()), Math.toRadians(7.0D)));
		}
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			Vec3d radial = spiderLegRadial(heading, index);
			Vec3d tucked = bodyCenter.add(radial.multiply(radialReach))
					.add(0.0D, -downwardReach, 0.0D);
			if (wallCapture && spiderLegRole(index) == 0) {
				tucked = tucked.add(captureDirection.multiply(bodyRadius * 1.08D))
						.add(0.0D, bodyRadius * 0.18D, 0.0D);
			}
			leg.foot = leg.foot.lerp(tucked, 0.30D);
			leg.target = tucked;
			leg.stepStart = leg.foot;
			leg.turningStep = false;
			setSpiderLegPhase(leg, SpiderLegPhase.SEARCHING);
			leg.supportBodyTarget = bodyCenter;
		}
		if (spiderWallCaptureTicks > 0) {
			spiderWallCaptureTicks--;
		}
		dataTracker.set(SPIDER_BODY_COMPRESSION, 0.0F);
	}

	private Vec3d spiderAirborneCaptureDirection(Vec3d heading) {
		Vec3d direction = dashTicks > 0 && dashDirection.lengthSquared() > 0.01D
				? dashDirection
				: getVelocity();
		direction = new Vec3d(direction.x, 0.0D, direction.z);
		if (direction.lengthSquared() < 0.01D) {
			direction = new Vec3d(heading.x, 0.0D, heading.z);
		}
		return direction.lengthSquared() < 0.01D
				? new Vec3d(0.0D, 0.0D, 1.0D)
				: direction.normalize();
	}

	private SpiderWallTarget findSpiderWallCaptureTarget(Vec3d heading) {
		if (!isSpiderMode() || spiderWallCaptureTicks <= 0) {
			return null;
		}
		Vec3d direction = spiderAirborneCaptureDirection(heading);
		Vec3d start = spiderBodyCenter();
		double range = spiderStandHeight() + getRingDiameter() * 0.92D;
		BlockHitResult hit = getWorld().raycast(new RaycastContext(start,
				start.add(direction.multiply(range)), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, this));
		if (hit.getType() == HitResult.Type.MISS) {
			return null;
		}
		Vec3d normal = Vec3d.of(hit.getSide().getVector());
		if (Math.abs(normal.y) > 0.42D || normal.dotProduct(direction) > -0.52D) {
			return null;
		}
		Vec3d bodyTarget = hit.getPos().add(normal.multiply(spiderStandHeight()));
		if (!isSpiderBodyRouteClear(bodyTarget, normal)) {
			return null;
		}
		spiderWallCaptureNormal = normal;
		return new SpiderWallTarget(hit.getPos(), normal);
	}

	private boolean trySpiderWallCapture(SpiderWallTarget target, Vec3d heading) {
		if (target == null || !isSpiderMode() || spiderWallCaptureTicks <= 0) {
			return false;
		}
		double contactDistance = spiderBodyCenter().distanceTo(target.point());
		if (!horizontalCollision && contactDistance > spiderStandHeight() + 0.72D) {
			return false;
		}
		Vec3d normal = target.normal().normalize();
		Vec3d wallForward = projectOntoPlane(Vec3d.of(Direction.UP.getVector()), normal);
		if (wallForward.lengthSquared() < 0.01D) {
			wallForward = spiderTravelForward(normal, heading);
		}
		setContactNormal(normal);
		setForwardVector(wallForward.normalize());
		setSpiderBodyNormal(stepSpiderBodyNormal(
				trackedSpiderBodyNormal(), normal, Math.toRadians(20.0D)));
		setVelocity(Vec3d.ZERO);
		velocityDirty = true;
		abilityAirborne = false;
		dashTicks = 0;
		spiderWallCaptureTicks = 0;
		spiderJumpDashWindowTicks = 0;
		spiderWallCaptureNormal = normal;
		contactMissingTicks = 0;
		contactSwitchCooldown = 2;
		beginSpiderLandingRecovery(heading, normal, 5);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_CHAIN_PLACE,
				SoundCategory.NEUTRAL, 0.72F, 0.70F);
		if (getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.CLOUD, target.point().x, target.point().y, target.point().z,
					10, 0.42D, 0.42D, 0.42D, 0.025D);
		}
		return true;
	}

	private void activateSpiderWallCapture() {
		if (!isSpiderMode()) {
			return;
		}
		spiderWallCaptureTicks = SPIDER_WALL_CAPTURE_TICKS;
		spiderWallCaptureNormal = Vec3d.ZERO;
	}

	private void beginSpiderLandingRecovery(Vec3d heading) {
		beginSpiderLandingRecovery(heading, Vec3d.of(Direction.UP.getVector()),
				SPIDER_LANDING_RECOVERY_TICKS);
	}

	private void beginSpiderLandingRecovery(Vec3d heading, Vec3d landingNormal, int recoveryTicks) {
		spiderWasAirborne = false;
		spiderLandingRecoveryDuration = Math.max(3, recoveryTicks);
		spiderLandingRecoveryTicks = spiderLandingRecoveryDuration;
		Vec3d normal = landingNormal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: landingNormal.normalize();
		setContactNormal(normal);
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d landingForward = projectOntoPlane(getForwardVector(), normal);
		if (landingForward.lengthSquared() < 0.01D) {
			landingForward = projectOntoPlane(Vec3d.of(Direction.UP.getVector()), normal);
		}
		if (landingForward.lengthSquared() < 0.01D) {
			landingForward = spiderTravelForward(normal, heading);
		}
		landingForward = landingForward.normalize();
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			Vec3d radial = spiderPlaneRadial(normal, landingForward, index);
			SpiderSurfaceTarget groundTarget = findSpiderLegSurface(bodyCenter, radial, normal);
			double radialDistance = groundTarget == null
					? spiderLegReach(getRingLevel())
					: groundTarget.radialDistance();
			Vec3d sample = bodyCenter.add(radial.multiply(radialDistance))
					.subtract(normal.multiply(spiderStandHeight()));
			Vec3d ground = groundTarget == null ? null : groundTarget.point();
			leg.stepStart = leg.foot;
			leg.target = ground == null ? sample : ground;
			leg.stepBodyTarget = leg.target.subtract(radial.multiply(radialDistance))
					.add(normal.multiply(spiderStandHeight()));
			leg.supportNormal = normal;
			leg.targetNormal = normal;
			leg.stepProgress = 0.0D;
			leg.turningStep = false;
			setSpiderLegPhase(leg, SpiderLegPhase.SWINGING);
		}
		dataTracker.set(SPIDER_BODY_COMPRESSION, 1.0F);
	}

	private void updateSpiderLandingRecovery() {
		double progress = 1.0D - (spiderLandingRecoveryTicks - 1.0D)
				/ Math.max(3.0D, spiderLandingRecoveryDuration);
		double eased = 1.0D - Math.pow(1.0D - progress, 3.0D);
		for (SpiderLegState leg : spiderLegs) {
			if (leg == null) {
				continue;
			}
			leg.foot = leg.stepStart.lerp(leg.target, eased)
					.add(leg.targetNormal.multiply(Math.sin(Math.PI * progress) * 0.16D));
			leg.stepProgress = progress;
		}
		dataTracker.set(SPIDER_BODY_COMPRESSION, (float) Math.max(0.0D, 1.0D - progress));
		spiderLandingRecoveryTicks--;
		if (spiderLandingRecoveryTicks <= 0) {
			for (SpiderLegState leg : spiderLegs) {
				if (leg == null) {
					continue;
				}
				leg.foot = leg.target;
				leg.supportBodyTarget = leg.stepBodyTarget;
				setSpiderLegPhase(leg, SpiderLegPhase.PLANTED);
			}
			dataTracker.set(SPIDER_BODY_COMPRESSION, 0.0F);
		}
	}

	private void ensureSpiderLegs(Vec3d normal, Vec3d travelForward) {
		Vec3d bodyCenter = spiderBodyCenter();
		double maximumFootDistance = spiderLegMaximumReach(getRingLevel()) + spiderStandHeight() + 1.5D;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			if (!hasFunctionalSpiderLeg(index)) {
				spiderLegs[index] = null;
				continue;
			}
			SpiderLegState leg = spiderLegs[index];
			if (leg != null) {
				if (!leg.stepping
						&& leg.foot.squaredDistanceTo(bodyCenter)
								> maximumFootDistance * maximumFootDistance) {
					// Keep the old foot as the start of a recovery step. Rebuilding every leg here
					// made all eight rendered feet snap back to their default pose at once.
					setSpiderLegPhase(leg, SpiderLegPhase.SEARCHING);
					leg.stableTicks = 0;
				}
				continue;
			}
			leg = spiderLegs[index] = new SpiderLegState();
			Vec3d radial = spiderPlaneRadial(normal, travelForward, index);
			SpiderSurfaceTarget surfaceTarget = findSpiderLegSurface(bodyCenter, radial, normal);
			double radialDistance = surfaceTarget == null
					? spiderLegReach(getRingLevel())
					: surfaceTarget.radialDistance();
			Vec3d fallback = bodyCenter.add(radial.multiply(radialDistance))
					.subtract(normal.multiply(spiderStandHeight()));
			Vec3d surface = surfaceTarget == null ? null : surfaceTarget.point();
			leg.foot = surface == null ? fallback : surface;
			leg.target = leg.foot;
			leg.stepStart = leg.foot;
			leg.supportBodyTarget = bodyCenter;
			leg.stepBodyTarget = bodyCenter;
			leg.supportNormal = normal;
			leg.targetNormal = normal;
			setSpiderLegPhase(leg, surface == null
					? SpiderLegPhase.SEARCHING
					: SpiderLegPhase.PLANTED);
			publishSpiderFoot(index, leg.foot, bodyCenter);
		}
		spiderFeetInitialized = true;
	}

	/** Plans footholds; planted feet remain the only source of chassis traction and body support. */
	private SpiderLegPlan updateSpiderLegPlanner(Vec3d normal, Vec3d travelForward,
			double throttle, double turnRadians) {
		boolean moving = Math.abs(throttle) > MIN_INPUT;
		boolean turning = Math.abs(turnRadians) > 1.0E-4D;
		int activeLegCount = getSpiderInstalledLegCount();
		if (activeLegCount <= 0) {
			spiderGaitRole = -1;
		}
		int directionSign = moving ? (throttle >= 0.0D ? 1 : -1) : spiderGaitDirectionSign;
		if (moving && directionSign != spiderGaitDirectionSign) {
			spiderGaitDirectionSign = directionSign;
			spiderGaitRole = -1;
		}
		if (activeLegCount > 0) {
			if (spiderGaitRole < 0) {
				spiderGaitRole = findInstalledSpiderGaitRole(directionSign >= 0 ? 0 : 3, directionSign);
			} else if ((moving || turning) && !hasSpiderSteppingRole(spiderGaitRole)) {
				spiderGaitRole = findInstalledSpiderGaitRole(
						nextSpiderGaitRole(spiderGaitRole, directionSign), directionSign);
			}
		}
		double speedScale = spiderCrawlSpeedScale();
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d commandDirection = moving
				? travelForward.multiply(Math.signum(throttle)).normalize()
				: Vec3d.ZERO;
		double stride = getRingDiameter() * SPIDER_STRIDE_RATIO;
		double predictionDistance = moving
				? Math.min(stride * 0.58D,
						Math.max(0.94D, spiderMaximumSpeed() * 0.52D))
				: 0.0D;
		Vec3d predictedBody = bodyCenter.add(commandDirection.multiply(predictionDistance));
		boolean probingWallCrest = moving && Math.abs(normal.y) < 0.35D
				&& commandDirection.y > 0.52D;
		Vec3d explorationOffset = moving
				? spiderExplorationOffset(normal, commandDirection)
				: Vec3d.ZERO;
		Vec3d requestedExplorationNormal = Vec3d.ZERO;
		double bestExplorationAlignment = 0.0D;
		Vec3d landedPositionSum = Vec3d.ZERO;
		int landedFeet = 0;
		int swingingFeet = 0;

		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			if (leg.stepping || leg.phase == SpiderLegPhase.SWINGING
					|| leg.phase == SpiderLegPhase.LANDING) {
				setSpiderLegPhase(leg, leg.stepProgress < 0.74D
						? SpiderLegPhase.SWINGING
						: SpiderLegPhase.LANDING);
				double distance = Math.max(0.25D, leg.stepStart.distanceTo(leg.target));
				double progressDelta = Math.min(SPIDER_MAX_STEP_PROGRESS_PER_TICK,
						SPIDER_LEG_MOVE_SPEED * speedScale / distance);
				leg.stepProgress = Math.min(1.0D, leg.stepProgress + progressDelta);
				double t = leg.stepProgress;
				double eased = t * t * (3.0D - 2.0D * t);
				double arc = Math.sin(Math.PI * t);
				Vec3d liftNormal = normal.add(leg.targetNormal);
				liftNormal = liftNormal.lengthSquared() < 0.25D ? normal : liftNormal.normalize();
				Vec3d radial = spiderPlaneRadial(normal, travelForward, index);
				Vec3d roleSwing = spiderLegRoleSwing(
						spiderLegRole(index), travelForward, radial,
						moving ? Math.signum(throttle) : 0.0D, arc);
				leg.foot = keepSpiderFootOutsideSurface(
						leg.stepStart.lerp(leg.target, eased)
								.add(liftNormal.multiply(SPIDER_LEG_LIFT
										* spiderLegLiftScale(spiderLegRole(index)) * arc))
								.add(roleSwing),
						leg.targetNormal);
				if (t >= 1.0D) {
					leg.foot = leg.target;
					leg.supportNormal = leg.targetNormal;
					leg.supportBodyTarget = leg.stepBodyTarget;
					leg.stableTicks = 0;
					setSpiderLegPhase(leg, SpiderLegPhase.PLANTED);
					landedPositionSum = landedPositionSum.add(leg.foot);
					landedFeet++;
				} else {
					swingingFeet++;
				}
			} else if (leg.grounded) {
				setSpiderLegPhase(leg, SpiderLegPhase.PLANTED);
				leg.stableTicks++;
			} else {
				setSpiderLegPhase(leg, SpiderLegPhase.SEARCHING);
			}
		}

		List<SpiderStepCandidate> candidates = new ArrayList<>();
		int reachableFeet = 0;
		int plantedFeet = spiderSupportCount();
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null || leg.stepping) {
				continue;
			}
			Vec3d radial = spiderPlaneRadial(normal, travelForward, index);
			if (turning && normal.y > 0.8D) {
				radial = rotateAroundAxis(radial, normal,
						turnRadians * SPIDER_TURN_FOOT_LEAD_TICKS).normalize();
			}
			int legRole = spiderLegRole(index);
			boolean directionalProbe = isSpiderDirectionalProbeLeg(
					legRole, moving ? Math.signum(throttle) : 1.0D);
			boolean descendingExploration = getSpiderExplorationMode() == SPIDER_EXPLORATION_DOWN;
			double explorationShare = directionalProbe ? 1.0D
					: descendingExploration
							? legRole == 1 || legRole == 2 ? 0.76D : 0.52D
							: legRole == 1 || legRole == 2 ? 0.42D : 0.0D;
			Vec3d legPredictedBody = predictedBody.add(explorationOffset.multiply(explorationShare));
			if (probingWallCrest && directionalProbe) {
				double reachBudget = Math.min(spiderLegMaximumReach(getRingLevel()) * 0.24D,
						getRingDiameter() * 0.82D);
				legPredictedBody = legPredictedBody
						.add(commandDirection.multiply(reachBudget * 0.52D))
						.subtract(normal.multiply(reachBudget * 0.62D));
			}
			SpiderFoothold foothold = findSpiderBestFoothold(
					legPredictedBody, radial, normal, commandDirection, leg);
			SpiderFoothold lowerFoothold = (foothold == null
					|| descendingExploration && foothold.bodyTarget().y >= bodyCenter.y - 0.24D)
							? findSpiderLowerFoothold(
									predictedBody, radial, normal, commandDirection, leg)
							: null;
			if (lowerFoothold != null && (foothold == null
					|| descendingExploration
							&& lowerFoothold.bodyTarget().y < bodyCenter.y - 0.24D)) {
				foothold = lowerFoothold;
			}
			if (foothold == null) {
				continue;
			}
			boolean advancingBodyTarget = !moving
					|| foothold.bodyTarget().subtract(bodyCenter).dotProduct(commandDirection) > 0.08D;
			if (advancingBodyTarget) {
				reachableFeet++;
			}
			double explorationAlignment = spiderExplorationAlignment(
					bodyCenter, foothold.bodyTarget());
			if (moving && explorationShare > 0.0D
					&& explorationAlignment > bestExplorationAlignment) {
				Vec3d tiltedNormal = spiderExplorationTiltNormal(normal, commandDirection);
				Vec3d blendedNormal = foothold.normal().multiply(0.58D)
						.add(tiltedNormal.multiply(0.42D));
				requestedExplorationNormal = blendedNormal.lengthSquared() < 0.25D
						? tiltedNormal
						: blendedNormal.normalize();
				bestExplorationAlignment = explorationAlignment;
			}

			Vec3d root = bodyCenter.add(radial.multiply(
					getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO));
			double rootLag = moving
					? root.subtract(leg.foot).dotProduct(commandDirection)
					: 0.0D;
			double stretchRatio = root.distanceTo(leg.foot)
					/ Math.max(0.25D, spiderLegMaximumReach(getRingLevel()));
			double targetDistance = leg.foot.distanceTo(foothold.point());
			boolean staleSurface = leg.phase == SpiderLegPhase.PLANTED
					&& leg.supportNormal.dotProduct(normal) < SPIDER_STALE_SUPPORT_DOT;
			boolean needsPlacement = leg.phase == SpiderLegPhase.SEARCHING;
			double triggerDistance = Math.max(SPIDER_LEG_TRIGGER_DISTANCE,
					stride * (turning ? 0.24D : 0.34D));
			double comfortDistance = Math.max(SPIDER_LEG_COMFORT_DISTANCE, stride * 0.72D);
			boolean draggedBehind = moving && rootLag > triggerDistance * 0.48D;
			boolean outsideTriggerZone = targetDistance > triggerDistance
					|| stretchRatio > SPIDER_LEG_TRIGGER_STRETCH || draggedBehind;
			boolean outsideComfortZone = targetDistance > comfortDistance
					|| stretchRatio > SPIDER_LEG_COMFORT_STRETCH
					|| (moving && rootLag > comfortDistance * 0.62D);
			boolean explorationDescent = descendingExploration
					&& foothold.bodyTarget().y < bodyCenter.y - 0.28D;
			boolean emergency = needsPlacement || staleSurface || outsideComfortZone
					|| explorationDescent;
			if (moving && !advancingBodyTarget && leg.phase == SpiderLegPhase.PLANTED
					&& !emergency) {
				continue;
			}
			if (leg.phase == SpiderLegPhase.PLANTED && leg.stableTicks < 3
					&& !emergency) {
				continue;
			}
			if (!emergency && !outsideTriggerZone) {
				continue;
			}

			double roleBias = switch (spiderLegRole(index)) {
				case 0 -> moving ? 0.55D : 0.0D;
				case 3 -> draggedBehind ? 0.85D : 0.0D;
				default -> 0.0D;
			};
			double groupBias = isSpiderFirstGaitGroup(index) == spiderPreviousFirstTripod
					? 0.32D : 0.0D;
			double urgency = (needsPlacement ? 8.0D : 0.0D)
					+ Math.max(0.0D, rootLag / Math.max(0.5D, stride)) * 4.0D
					+ Math.max(0.0D, stretchRatio - 0.60D) * 5.0D
					+ Math.min(2.0D, targetDistance / Math.max(0.5D, stride))
					+ (outsideComfortZone ? 2.4D : 0.0D)
					+ (staleSurface ? 3.0D : 0.0D)
					+ (explorationDescent ? 2.2D * explorationShare : 0.0D)
					+ (foothold.pathObstructed() ? 1.25D : 0.0D)
					+ roleBias + groupBias - foothold.score() * 0.15D;
			candidates.add(new SpiderStepCandidate(index, foothold, urgency, emergency,
					isSpiderFirstGaitGroup(index)));
		}

		candidates.sort((left, right) -> Double.compare(right.urgency(), left.urgency()));
		int gatedRole = spiderGaitRole;
		if (moving && gatedRole >= 0 && !hasSpiderSteppingRole(gatedRole)
				&& !hasSpiderCandidateForRole(candidates, gatedRole)) {
			int role = gatedRole;
			for (int attempt = 0; attempt < 3; attempt++) {
				role = findInstalledSpiderGaitRole(nextSpiderGaitRole(role, directionSign), directionSign);
				if (hasSpiderCandidateForRole(candidates, role)) {
					gatedRole = role;
					spiderGaitRole = role;
					break;
				}
			}
		}
		boolean coordinatedDescent = moving
				&& getSpiderExplorationMode() == SPIDER_EXPLORATION_DOWN;
		int maximumSwinging = activeLegCount <= 2 ? 1
				: Math.min(2, activeLegCount - 2);
		int minimumPlanted = activeLegCount <= 2 ? 0 : Math.min(3, activeLegCount - 1);
		int startedFeet = 0;
		Boolean startedGaitGroup = null;
		int startedGaitRole = -1;
		for (SpiderStepCandidate candidate : candidates) {
			if (swingingFeet + startedFeet >= maximumSwinging) {
				break;
			}
			SpiderLegState leg = spiderLegs[candidate.legIndex()];
			if (leg == null || leg.stepping) {
				continue;
			}
			int candidateRole = spiderLegRole(candidate.legIndex());
			boolean sameRoleBatch = startedGaitRole >= 0 && candidateRole == startedGaitRole;
			if (moving && gatedRole >= 0 && candidateRole != gatedRole) {
				continue;
			}
			if (startedGaitRole >= 0 && !sameRoleBatch) {
				continue;
			}
			boolean wasPlanted = leg.phase == SpiderLegPhase.PLANTED;
			if (wasPlanted && plantedFeet - 1 < minimumPlanted) {
				continue;
			}
			if (startedGaitGroup != null
					&& candidate.firstGaitGroup() != startedGaitGroup
					&& !sameRoleBatch && !candidate.emergency()) {
				continue;
			}
			if (wasPlanted && !hasStableSpiderSupportAfterLift(
					candidate.legIndex(), bodyCenter, normal, travelForward, candidate.emergency())
					&& !(coordinatedDescent && candidate.emergency() && plantedFeet >= 4)) {
				continue;
			}
			if (hasSpiderStepConflict(candidate.legIndex(), candidate.emergency(), sameRoleBatch)) {
				continue;
			}
			SpiderFoothold foothold = candidate.foothold();
			leg.stepStart = leg.foot;
			leg.target = foothold.point();
			leg.targetNormal = foothold.normal();
			leg.stepProgress = 0.0D;
			leg.stepBodyTarget = foothold.bodyTarget();
			leg.lastStepSerial = spiderGaitHalfSerial + 1;
			setSpiderLegPhase(leg, SpiderLegPhase.SWINGING);
			if (wasPlanted) {
				plantedFeet--;
			}
			if (startedGaitGroup == null) {
				startedGaitGroup = candidate.firstGaitGroup();
			}
			if (startedGaitRole < 0) {
				startedGaitRole = candidateRole;
				spiderGaitRole = candidateRole;
			}
			startedFeet++;
		}
		if (startedFeet > 0) {
			spiderGaitHalfSerial++;
			spiderPreviousFirstTripod = startedGaitGroup == null
					? !spiderPreviousFirstTripod
					: !startedGaitGroup;
		}
		updateSpiderExplorationNormalTarget(requestedExplorationNormal);

		if (spiderLastActualTravel > 0.002D) {
			spiderGaitPhase = (spiderGaitPhase
					+ (float) Math.min(0.18D, spiderLastActualTravel / Math.max(0.5D, stride))) % 1.0F;
		}
		dataTracker.set(SPIDER_GAIT_PHASE, spiderGaitPhase);
		if (spiderSurfaceTransitionTicks > 0) {
			spiderSurfaceTransitionTicks--;
		}
		if (landedFeet > 0) {
			playSpiderStepSound(landedPositionSum.multiply(1.0D / landedFeet), speedScale);
		}
		boolean routeAvailable = !moving
				? plantedFeet + swingingFeet + startedFeet > 0
				: reachableFeet > 0;
		return new SpiderLegPlan(routeAvailable, reachableFeet, plantedFeet);
	}

	private void setSpiderLegPhase(SpiderLegState leg, SpiderLegPhase phase) {
		leg.phase = phase;
		leg.grounded = phase == SpiderLegPhase.PLANTED;
		leg.stepping = phase == SpiderLegPhase.SWINGING || phase == SpiderLegPhase.LANDING;
	}

	private Vec3d spiderExplorationOffset(Vec3d normal, Vec3d commandDirection) {
		int mode = getSpiderExplorationMode();
		if (mode == SPIDER_EXPLORATION_AUTO || commandDirection.lengthSquared() < 0.5D) {
			return Vec3d.ZERO;
		}
		double sign = mode == SPIDER_EXPLORATION_UP ? 1.0D : -1.0D;
		double verticalDistance = Math.min(spiderLegMaximumReach(getRingLevel()) * 0.68D,
				getRingDiameter() * 1.50D);
		double forwardDistance = getRingDiameter() * 0.58D;
		Vec3d vertical = Vec3d.of(Direction.UP.getVector()).multiply(sign * verticalDistance);
		Vec3d forward = commandDirection.normalize().multiply(forwardDistance);
		return vertical.add(forward);
	}

	private double spiderExplorationAlignment(Vec3d bodyCenter, Vec3d bodyTarget) {
		return switch (getSpiderExplorationMode()) {
			case SPIDER_EXPLORATION_UP -> bodyTarget.y - bodyCenter.y;
			case SPIDER_EXPLORATION_DOWN -> bodyCenter.y - bodyTarget.y;
			default -> 0.0D;
		};
	}

	private Vec3d spiderExplorationTiltNormal(Vec3d normal, Vec3d commandDirection) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d tangent = projectOntoPlane(commandDirection, safeNormal);
		if (tangent.lengthSquared() < 0.01D || getSpiderExplorationMode() == SPIDER_EXPLORATION_AUTO) {
			return safeNormal;
		}
		double tilt = getSpiderExplorationMode() == SPIDER_EXPLORATION_UP ? -0.62D : 0.62D;
		return safeNormal.add(tangent.normalize().multiply(tilt)).normalize();
	}

	private void updateSpiderExplorationNormalTarget(Vec3d requestedNormal) {
		if (getSpiderExplorationMode() == SPIDER_EXPLORATION_AUTO
				|| requestedNormal.lengthSquared() < 0.25D) {
			spiderExplorationNormalTarget = spiderExplorationNormalTarget.multiply(0.72D);
			if (spiderExplorationNormalTarget.lengthSquared() < 0.01D) {
				spiderExplorationNormalTarget = Vec3d.ZERO;
			}
			return;
		}
		Vec3d requested = requestedNormal.normalize();
		if (spiderExplorationNormalTarget.lengthSquared() < 0.25D) {
			spiderExplorationNormalTarget = requested;
			return;
		}
		Vec3d blended = spiderExplorationNormalTarget.normalize().lerp(requested, 0.32D);
		spiderExplorationNormalTarget = blended.lengthSquared() < 0.25D
				? requested
				: blended.normalize();
	}

	/** Rejects footholds whose solved chassis pose cannot clear the terrain between poses. */
	private boolean isSpiderBodyRouteClear(Vec3d targetCenter, Vec3d targetNormal) {
		Vec3d startCenter = spiderBodyCenter();
		Vec3d displacement = targetCenter.subtract(startCenter);
		if (displacement.length() > spiderLegMaximumReach(getRingLevel()) * 0.92D) {
			return false;
		}
		Vec3d startNormal = trackedSpiderBodyNormal();
		Vec3d endNormal = targetNormal.lengthSquared() < 0.5D
				? startNormal
				: targetNormal.normalize();
		if (startNormal.dotProduct(endNormal) < -0.20D) {
			endNormal = startNormal.add(endNormal.multiply(0.35D)).normalize();
		}
		boolean directRouteClear = true;
		for (double progress : new double[]{0.40D, 0.72D, 1.0D}) {
			Vec3d center = startCenter.lerp(targetCenter, progress);
			Vec3d blendedNormal = startNormal.lerp(endNormal, progress);
			Vec3d poseNormal = blendedNormal.lengthSquared() < 0.25D
					? endNormal
					: blendedNormal.normalize();
			Box clearance = spiderBodyClearanceBounds(center, poseNormal).contract(0.14D);
			if (!getWorld().isSpaceEmpty(this, clearance)) {
				directRouteClear = false;
				break;
			}
		}
		return directRouteClear || isSpiderWallCrestRouteClear(
				startCenter, targetCenter, startNormal, endNormal);
	}

	/** Checks the natural two-stage path around a wall lip: rise beside it, then move over it. */
	private boolean isSpiderWallCrestRouteClear(Vec3d startCenter, Vec3d targetCenter,
			Vec3d startNormal, Vec3d endNormal) {
		if (Math.abs(startNormal.y) > 0.35D || endNormal.y < 0.80D
				|| targetCenter.y <= startCenter.y + 0.18D) {
			return false;
		}
		Vec3d wallOutward = startNormal.normalize();
		Vec3d liftedCenter = new Vec3d(startCenter.x, targetCenter.y, startCenter.z)
				.add(wallOutward.multiply(0.12D));
		for (double progress : new double[]{0.38D, 0.68D, 1.0D}) {
			Vec3d center = startCenter.lerp(liftedCenter, progress);
			if (!getWorld().isSpaceEmpty(this,
					spiderBodyClearanceBounds(center, startNormal).contract(0.16D))) {
				return false;
			}
		}
		for (double progress : new double[]{0.34D, 0.68D, 1.0D}) {
			Vec3d center = liftedCenter.lerp(targetCenter, progress);
			Vec3d blended = startNormal.lerp(endNormal, progress);
			Vec3d poseNormal = blended.lengthSquared() < 0.25D
					? endNormal
					: blended.normalize();
			if (!getWorld().isSpaceEmpty(this,
					spiderBodyClearanceBounds(center, poseNormal).contract(0.16D))) {
				return false;
			}
		}
		return true;
	}

	private Box spiderBodyClearanceBounds(Vec3d bodyCenter, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double radius = getRingDiameter() * 0.5D;
		double halfThickness = 0.42D + getRingLevel() * 0.08D;
		double xRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.x * safeNormal.x))
				+ halfThickness * Math.abs(safeNormal.x);
		double yRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.y * safeNormal.y))
				+ halfThickness * Math.abs(safeNormal.y);
		double zRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.z * safeNormal.z))
				+ halfThickness * Math.abs(safeNormal.z);
		return new Box(bodyCenter.x - xRadius, bodyCenter.y - yRadius, bodyCenter.z - zRadius,
				bodyCenter.x + xRadius, bodyCenter.y + yRadius, bodyCenter.z + zRadius);
	}

	/** Prevents a step from removing the last geometrically useful support under the chassis. */
	private boolean hasStableSpiderSupportAfterLift(int excludedLeg, Vec3d bodyCenter,
			Vec3d normal, Vec3d travelForward, boolean emergency) {
		if (getSpiderInstalledLegCount() <= 3) {
			return true;
		}
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d forward = projectOntoPlane(travelForward, safeNormal);
		if (forward.lengthSquared() < 0.01D) {
			forward = spiderTravelForward(safeNormal, getBodyHeading());
		}
		forward = forward.normalize();
		Vec3d side = safeNormal.crossProduct(forward);
		if (side.lengthSquared() < 0.01D) {
			return true;
		}
		side = side.normalize();

		List<SpiderSupportPoint> supports = new ArrayList<>();
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (index == excludedLeg || leg == null || !leg.grounded || leg.stepping
					|| leg.supportNormal.dotProduct(safeNormal) < -0.25D) {
				continue;
			}
			Vec3d offset = leg.foot.subtract(bodyCenter);
			SpiderSupportPoint point = new SpiderSupportPoint(
					offset.dotProduct(side), offset.dotProduct(forward));
			boolean duplicate = false;
			for (SpiderSupportPoint existing : supports) {
				if (spiderSupportDistanceSquared(existing, point) < 1.0E-4D) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) {
				supports.add(point);
			}
		}
		if (supports.size() < 3) {
			return true;
		}
		List<SpiderSupportPoint> hull = spiderSupportHull(supports);
		if (hull.size() < 3 || spiderSupportHullContainsOrigin(hull)) {
			return true;
		}
		double nearestDistance = Double.POSITIVE_INFINITY;
		for (int index = 0; index < hull.size(); index++) {
			SpiderSupportPoint start = hull.get(index);
			SpiderSupportPoint end = hull.get((index + 1) % hull.size());
			nearestDistance = Math.min(nearestDistance,
					spiderDistanceFromOriginToSegment(start, end));
		}
		double leeway = getRingDiameter() * SPIDER_SUPPORT_POLYGON_LEEWAY_RATIO
				* (emergency ? 1.55D : 1.0D);
		return nearestDistance <= leeway;
	}

	/** Pulls an unsupported centre of mass back toward the polygon formed by planted feet. */
	private Vec3d spiderSupportRecovery(Vec3d bodyCenter, Vec3d normal, Vec3d travelForward) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d forward = projectOntoPlane(travelForward, safeNormal);
		if (forward.lengthSquared() < 0.01D) {
			forward = spiderTravelForward(safeNormal, getBodyHeading());
		}
		forward = forward.normalize();
		Vec3d side = safeNormal.crossProduct(forward);
		if (side.lengthSquared() < 0.01D) {
			return Vec3d.ZERO;
		}
		side = side.normalize();

		List<SpiderSupportPoint> supports = new ArrayList<>();
		for (SpiderLegState leg : spiderLegs) {
			if (leg == null || !leg.grounded || leg.stepping
					|| leg.supportNormal.dotProduct(safeNormal) < -0.25D) {
				continue;
			}
			Vec3d offset = leg.foot.subtract(bodyCenter);
			supports.add(new SpiderSupportPoint(
					offset.dotProduct(side), offset.dotProduct(forward)));
		}
		if (supports.size() < 3) {
			return Vec3d.ZERO;
		}
		List<SpiderSupportPoint> hull = spiderSupportHull(supports);
		if (hull.size() < 3 || spiderSupportHullContainsOrigin(hull)) {
			return Vec3d.ZERO;
		}
		SpiderSupportPoint nearest = null;
		double nearestDistanceSquared = Double.POSITIVE_INFINITY;
		for (int index = 0; index < hull.size(); index++) {
			SpiderSupportPoint candidate = spiderNearestPointToOriginOnSegment(
					hull.get(index), hull.get((index + 1) % hull.size()));
			double distanceSquared = candidate.x() * candidate.x() + candidate.y() * candidate.y();
			if (distanceSquared < nearestDistanceSquared) {
				nearest = candidate;
				nearestDistanceSquared = distanceSquared;
			}
		}
		if (nearest == null) {
			return Vec3d.ZERO;
		}
		double distance = Math.sqrt(nearestDistanceSquared);
		double leeway = getRingDiameter() * 0.045D;
		if (distance <= leeway) {
			return Vec3d.ZERO;
		}
		Vec3d correction = side.multiply(nearest.x()).add(forward.multiply(nearest.y()));
		double speed = Math.min(0.14D, (distance - leeway) * 0.20D);
		return correction.lengthSquared() < 1.0E-6D
				? Vec3d.ZERO
				: correction.normalize().multiply(speed);
	}

	private boolean hasSpiderStepConflict(int legIndex, boolean emergency, boolean allowRolePair) {
		for (int otherIndex = 0; otherIndex < SPIDER_LEG_COUNT; otherIndex++) {
			if (otherIndex == legIndex || !areAdjacentInstalledSpiderLegs(legIndex, otherIndex)) {
				continue;
			}
			SpiderLegState other = spiderLegs[otherIndex];
			if (other == null) {
				continue;
			}
			if (allowRolePair && spiderLegRole(otherIndex) == spiderLegRole(legIndex)) {
				continue;
			}
			if (other.stepping || (!emergency && other.grounded
					&& other.stableTicks < SPIDER_ADJACENT_STEP_COOLDOWN_TICKS)) {
				return true;
			}
		}
		return false;
	}

	private boolean areAdjacentInstalledSpiderLegs(int first, int second) {
		for (int direction : new int[]{-1, 1}) {
			for (int distance = 1; distance < SPIDER_LEG_COUNT; distance++) {
				int candidate = Math.floorMod(first + direction * distance, SPIDER_LEG_COUNT);
				if (!hasFunctionalSpiderLeg(candidate)) {
					continue;
				}
				if (candidate == second) {
					return true;
				}
				break;
			}
		}
		return false;
	}

	private static List<SpiderSupportPoint> spiderSupportHull(List<SpiderSupportPoint> points) {
		List<SpiderSupportPoint> sorted = new ArrayList<>(points);
		sorted.sort((left, right) -> {
			int x = Double.compare(left.x(), right.x());
			return x != 0 ? x : Double.compare(left.y(), right.y());
		});
		List<SpiderSupportPoint> hull = new ArrayList<>();
		for (SpiderSupportPoint point : sorted) {
			while (hull.size() >= 2 && spiderSupportCross(
					hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 1.0E-7D) {
				hull.remove(hull.size() - 1);
			}
			hull.add(point);
		}
		int lowerSize = hull.size();
		for (int index = sorted.size() - 2; index >= 0; index--) {
			SpiderSupportPoint point = sorted.get(index);
			while (hull.size() > lowerSize && spiderSupportCross(
					hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 1.0E-7D) {
				hull.remove(hull.size() - 1);
			}
			hull.add(point);
		}
		if (hull.size() > 1) {
			hull.remove(hull.size() - 1);
		}
		return hull;
	}

	private static boolean spiderSupportHullContainsOrigin(List<SpiderSupportPoint> hull) {
		for (int index = 0; index < hull.size(); index++) {
			SpiderSupportPoint start = hull.get(index);
			SpiderSupportPoint end = hull.get((index + 1) % hull.size());
			if ((end.x() - start.x()) * -start.y()
					- (end.y() - start.y()) * -start.x() < -1.0E-7D) {
				return false;
			}
		}
		return true;
	}

	private static SpiderSupportPoint spiderNearestPointToOriginOnSegment(
			SpiderSupportPoint start, SpiderSupportPoint end) {
		double dx = end.x() - start.x();
		double dy = end.y() - start.y();
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared < 1.0E-8D) {
			return start;
		}
		double t = MathHelper.clamp(-(start.x() * dx + start.y() * dy) / lengthSquared,
				0.0D, 1.0D);
		return new SpiderSupportPoint(start.x() + dx * t, start.y() + dy * t);
	}

	private static double spiderDistanceFromOriginToSegment(
			SpiderSupportPoint start, SpiderSupportPoint end) {
		SpiderSupportPoint nearest = spiderNearestPointToOriginOnSegment(start, end);
		return Math.sqrt(nearest.x() * nearest.x() + nearest.y() * nearest.y());
	}

	private static double spiderSupportCross(
			SpiderSupportPoint origin, SpiderSupportPoint first, SpiderSupportPoint second) {
		return (first.x() - origin.x()) * (second.y() - origin.y())
				- (first.y() - origin.y()) * (second.x() - origin.x());
	}

	private static double spiderSupportDistanceSquared(
			SpiderSupportPoint first, SpiderSupportPoint second) {
		double dx = first.x() - second.x();
		double dy = first.y() - second.y();
		return dx * dx + dy * dy;
	}

	/** Finds one reachable foothold across the current face and its adjacent block faces. */
	private SpiderFoothold findSpiderBestFoothold(Vec3d predictedBody, Vec3d radial,
			Vec3d routeNormal, Vec3d commandDirection, SpiderLegState leg) {
		Vec3d safeRouteNormal = routeNormal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: routeNormal.normalize();
		double legExtension = leg.foot.distanceTo(spiderBodyCenter());
		boolean needsSideBrace = getSpiderExplorationMode() != SPIDER_EXPLORATION_AUTO
				|| leg.phase == SpiderLegPhase.SEARCHING
				|| legExtension > spiderLegReach(getRingLevel()) * 0.88D;
		Vec3d[] normals = new Vec3d[9];
		int normalCount = 0;
		normals[normalCount++] = safeRouteNormal;
		if (leg.supportNormal.lengthSquared() > 0.5D
				&& leg.supportNormal.normalize().dotProduct(safeRouteNormal) >= -0.35D
				&& Math.abs(leg.supportNormal.normalize().dotProduct(safeRouteNormal)) < 0.985D) {
			normals[normalCount++] = leg.supportNormal.normalize();
		}
		for (Direction direction : Direction.values()) {
			Vec3d candidate = Vec3d.of(direction.getVector());
			if (candidate.dotProduct(safeRouteNormal) < -0.35D
					|| !needsSideBrace && candidate.dotProduct(radial) > 0.45D) {
				continue;
			}
			boolean duplicate = false;
			for (int existing = 0; existing < normalCount; existing++) {
				if (normals[existing].dotProduct(candidate) > 0.985D) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) {
				normals[normalCount++] = candidate;
			}
		}

		SpiderFoothold best = null;
		for (int index = 0; index < normalCount; index++) {
			Vec3d candidateNormal = normals[index];
			SpiderFoothold candidate = evaluateSpiderFoothold(
					predictedBody, radial, safeRouteNormal, candidateNormal,
					commandDirection, leg, index == 0);
			if (candidate != null && (best == null || candidate.score() < best.score())) {
				best = candidate;
			}
		}
		return best;
	}

	/** Finds the nearest lower support before a side leg gives up or probes into a distant cave. */
	private SpiderFoothold findSpiderLowerFoothold(Vec3d predictedBody, Vec3d radial,
			Vec3d routeNormal, Vec3d commandDirection, SpiderLegState leg) {
		Vec3d safeNormal = routeNormal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: routeNormal.normalize();
		if (safeNormal.y < 0.68D) {
			return null;
		}
		boolean forceDescent = getSpiderExplorationMode() == SPIDER_EXPLORATION_DOWN;
		Vec3d bodyCenter = spiderBodyCenter();
		double nominalReach = spiderLegReach(getRingLevel());
		double maximumReach = spiderLegMaximumReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = bodyCenter.add(radial.multiply(rootRadius));
		boolean unsupportedOrExtended = leg.phase == SpiderLegPhase.SEARCHING
				|| root.distanceTo(leg.foot) > nominalReach * 0.82D;
		if (!forceDescent && !unsupportedOrExtended) {
			return null;
		}
		Vec3d surfaceTravel = projectOntoPlane(commandDirection, safeNormal);
		if (surfaceTravel.lengthSquared() < 0.01D) {
			surfaceTravel = projectOntoPlane(getForwardVector(), safeNormal);
		}
		if (surfaceTravel.lengthSquared() > 0.01D) {
			surfaceTravel = surfaceTravel.normalize();
		} else {
			surfaceTravel = Vec3d.ZERO;
		}
		double lead = getRingDiameter() * (forceDescent ? 0.62D : 0.28D);
		Vec3d probeBody = bodyCenter.add(surfaceTravel.multiply(lead));
		SpiderFoothold best = null;
		for (double scale : new double[]{0.24D, 0.38D, 0.54D, 0.70D, 0.86D, 0.98D}) {
			double radialDistance = nominalReach * scale;
			Vec3d column = probeBody.add(radial.multiply(radialDistance));
			Vec3d start = column.subtract(safeNormal.multiply(
					Math.max(0.25D, spiderStandHeight() - 0.90D)));
			Vec3d end = column.subtract(safeNormal.multiply(
					spiderStandHeight() + maximumReach * (forceDescent ? 1.02D : 0.78D)));
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start, end,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() == HitResult.Type.MISS
					|| Vec3d.of(hit.getSide().getVector()).dotProduct(safeNormal) < 0.55D) {
				continue;
			}
			SpiderSurfaceTarget validated = validateSpiderLegTarget(
					predictedBody, root, radial, safeNormal, hit.getPos(),
					radialDistance, maximumReach, true);
			if (validated == null) {
				continue;
			}
			Vec3d solvedBodyTarget = validated.point()
					.subtract(radial.multiply(validated.radialDistance()))
					.add(safeNormal.multiply(spiderStandHeight()));
			if (!isSpiderBodyRouteClear(solvedBodyTarget, safeNormal)) {
				continue;
			}
			double drop = bodyCenter.subtract(solvedBodyTarget).dotProduct(safeNormal);
			double continuity = validated.point().distanceTo(leg.foot)
					/ Math.max(0.5D, nominalReach);
			double score = continuity * 0.24D - MathHelper.clamp(drop / nominalReach, 0.0D, 1.0D) * 0.30D;
			SpiderFoothold candidate = new SpiderFoothold(validated.point(), safeNormal,
					solvedBodyTarget, validated.radialDistance(), score, validated.pathObstructed());
			if (best == null || candidate.score() < best.score()) {
				best = candidate;
			}
		}
		return best;
	}

	private SpiderFoothold evaluateSpiderFoothold(Vec3d predictedBody, Vec3d bodyRadial,
			Vec3d routeNormal, Vec3d candidateNormal, Vec3d commandDirection,
			SpiderLegState leg, boolean primarySurface) {
		Vec3d candidateTravel = projectOntoPlane(commandDirection, candidateNormal);
		if (candidateTravel.lengthSquared() < 0.01D) {
			candidateTravel = projectOntoPlane(Vec3d.of(Direction.UP.getVector()), candidateNormal);
		}
		if (candidateTravel.lengthSquared() < 0.01D) {
			candidateTravel = projectOntoPlane(getForwardVector(), candidateNormal);
		}
		if (candidateTravel.lengthSquared() < 0.01D) {
			candidateTravel = bodyRadial;
		}
		candidateTravel = candidateTravel.normalize();
		Vec3d surfaceSide = candidateNormal.crossProduct(candidateTravel);
		if (surfaceSide.lengthSquared() < 0.01D) {
			return null;
		}
		surfaceSide = surfaceSide.normalize();
		double sideSign = bodyRadial.dotProduct(surfaceSide) >= 0.0D ? 1.0D : -1.0D;
		Vec3d candidateRadial = projectOntoPlane(bodyRadial, candidateNormal);
		if (candidateRadial.lengthSquared() < 0.04D) {
			candidateRadial = candidateTravel.add(surfaceSide.multiply(sideSign * 0.72D));
		}
		candidateRadial = candidateRadial.normalize();

		double nominalReach = spiderLegReach(getRingLevel());
		double maximumReach = spiderLegMaximumReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = spiderBodyCenter().add(bodyRadial.multiply(rootRadius));
		double offsetStep = Math.max(0.52D, getRingDiameter() * 0.22D);
		Vec3d[] offsets = primarySurface
				? new Vec3d[]{Vec3d.ZERO,
						candidateTravel.multiply(offsetStep),
						surfaceSide.multiply(sideSign * offsetStep)}
				: new Vec3d[]{Vec3d.ZERO};
		SpiderFoothold best = null;
		for (Vec3d offset : offsets) {
			for (double scale : SPIDER_FOOTHOLD_RADIAL_SCALES) {
				double radialDistance = nominalReach * scale;
				Vec3d bodyTarget = predictedBody.add(offset);
				Vec3d sample = bodyTarget.add(candidateRadial.multiply(radialDistance))
						.subtract(candidateNormal.multiply(spiderStandHeight()));
				Vec3d surface = findSpiderSurface(sample, candidateNormal, 1.65D, 1.45D);
				if (surface == null) {
					continue;
				}
				SpiderSurfaceTarget validated = validateSpiderLegTarget(
						bodyTarget, root, bodyRadial, candidateNormal,
						surface, radialDistance, maximumReach, true);
				if (validated == null) {
					continue;
				}
				double rootDistance = root.distanceTo(validated.point());
				double reachPenalty = Math.abs(rootDistance - nominalReach * 0.68D)
						/ Math.max(0.5D, nominalReach);
				double routePenalty = (1.0D - candidateNormal.dotProduct(routeNormal)) * 0.34D;
				double continuityPenalty = (1.0D - candidateNormal.dotProduct(leg.supportNormal)) * 0.16D;
				double advance = commandDirection.lengthSquared() > 0.5D
						? validated.point().subtract(leg.foot).dotProduct(commandDirection)
						: 0.0D;
				double score = reachPenalty + routePenalty + continuityPenalty
						- MathHelper.clamp(advance / Math.max(0.5D, nominalReach), -0.4D, 0.4D)
						+ (validated.pathObstructed() ? 0.12D : 0.0D);
				if (best != null && score >= best.score()) {
					continue;
				}
				Vec3d solvedBodyTarget = validated.point()
						.subtract(candidateRadial.multiply(validated.radialDistance()))
						.add(candidateNormal.multiply(spiderStandHeight()));
				if (!isSpiderBodyRouteClear(solvedBodyTarget, candidateNormal)) {
					continue;
				}
				SpiderFoothold foothold = new SpiderFoothold(
						validated.point(), candidateNormal, solvedBodyTarget,
						validated.radialDistance(),
						score, validated.pathObstructed());
				best = foothold;
			}
		}
		return best;
	}

	/** Planted feet pull the chassis toward their solved body anchors; input only chooses targets. */
	private Vec3d solveSpiderBodyVelocity(Vec3d normal, Vec3d travelForward, double throttle,
			boolean floating, SpiderLegPlan legPlan) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d pathNormal = trackedSpiderBodyNormal();
		if (pathNormal.lengthSquared() < 0.5D) {
			pathNormal = safeNormal;
		}
		if (pathNormal.dotProduct(safeNormal) < 0.0D) {
			pathNormal = pathNormal.negate();
		}
		boolean moving = Math.abs(throttle) > MIN_INPUT;
		Vec3d rawCommandDirection = moving
				? travelForward.multiply(Math.signum(throttle)).normalize()
				: Vec3d.ZERO;
		Vec3d commandDirection = projectOntoPlane(rawCommandDirection, pathNormal);
		if (moving && commandDirection.lengthSquared() < 0.01D) {
			commandDirection = rawCommandDirection;
		}
		if (commandDirection.lengthSquared() > 0.01D) {
			commandDirection = commandDirection.normalize();
		}
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d tractionSum = Vec3d.ZERO;
		double tractionWeight = 0.0D;
		double supportNormalError = 0.0D;
		double supportNormalWeight = 0.0D;
		double stride = getRingDiameter() * SPIDER_STRIDE_RATIO;
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null || !leg.grounded || leg.stepping) {
				continue;
			}
			Vec3d supportNormal = leg.supportNormal.lengthSquared() < 0.5D
					? pathNormal
					: leg.supportNormal.normalize();
			if (supportNormal.dotProduct(pathNormal) < -0.20D) {
				continue;
			}
			Vec3d anchorDelta = leg.supportBodyTarget.subtract(bodyCenter);
			if (anchorDelta.length() > spiderLegMaximumReach(getRingLevel()) * 1.35D) {
				continue;
			}
			double stableBlend = MathHelper.clamp((leg.stableTicks + 1.0D) / 4.0D, 0.28D, 1.0D);
			supportNormalError += anchorDelta.dotProduct(pathNormal) * stableBlend;
			supportNormalWeight += stableBlend;
			if (!moving || commandDirection.lengthSquared() < 0.5D) {
				continue;
			}
			Vec3d radial = spiderPlaneRadial(pathNormal, commandDirection, index);
			Vec3d root = bodyCenter.add(radial.multiply(rootRadius));
			double draggedDistance = Math.max(0.0D,
					root.subtract(leg.foot).dotProduct(commandDirection));
			double releaseBlend = 1.0D - MathHelper.clamp(
					(draggedDistance - stride * 0.42D) / Math.max(0.45D, stride * 0.72D),
					0.0D, 0.88D);
			double forwardPull = anchorDelta.dotProduct(commandDirection);
			if (forwardPull <= 0.018D) {
				continue;
			}
			Vec3d tangentPull = projectOntoPlane(anchorDelta, pathNormal);
			double projectedForward = tangentPull.dotProduct(commandDirection);
			if (projectedForward <= 0.0D) {
				continue;
			}
			Vec3d lateralPull = tangentPull.subtract(
					commandDirection.multiply(projectedForward));
			int role = spiderLegRole(index);
			int pullRole = throttle >= 0.0D ? 0 : 3;
			int pushRole = throttle >= 0.0D ? 3 : 0;
			double roleWeight = role == pullRole ? 1.48D
					: role == pushRole ? 1.28D
							: role == 1 || role == 2 ? 0.92D : 1.0D;
			double roleDrive = role == pullRole ? 1.38D
					: role == pushRole ? 1.22D
							: role == 2 ? 0.82D : 1.0D;
			double weight = stableBlend * releaseBlend * roleWeight;
			tractionSum = tractionSum.add(
					commandDirection.multiply(projectedForward * roleDrive)
							.add(lateralPull.multiply(0.22D))
							.multiply(weight));
			tractionWeight += weight;
		}

		Vec3d currentTangent = projectOntoPlane(getVelocity(), pathNormal);
		Vec3d desiredTangent = Vec3d.ZERO;
		double maximumSpeed = spiderMaximumSpeed() * Math.min(1.0D, Math.abs(throttle));
		if (tractionWeight > 0.001D) {
			desiredTangent = tractionSum.multiply(1.0D / tractionWeight)
					.multiply(SPIDER_BODY_TRACTION * 0.55D * spiderCrawlSpeedScale());
			if (desiredTangent.length() > maximumSpeed) {
				desiredTangent = desiredTangent.normalize().multiply(maximumSpeed);
			}
		}
		double response = tractionWeight > 0.001D ? 0.58D : 0.66D;
		Vec3d tangent = currentTangent.lerp(desiredTangent, response);

		double contactCorrection = supportNormalWeight > 0.001D
				? MathHelper.clamp(supportNormalError / supportNormalWeight * 0.34D, -0.24D, 0.24D)
				: 0.0D;
		Vec3d surface = findSpiderSurface(
				bodyCenter.subtract(safeNormal.multiply(spiderStandHeight())),
				safeNormal, 1.05D, 1.45D);
		if (surface != null) {
			double standoffError = surface.add(safeNormal.multiply(spiderStandHeight()))
					.subtract(bodyCenter).dotProduct(safeNormal);
			contactCorrection += MathHelper.clamp(standoffError * 0.24D, -0.16D, 0.16D);
			contactCorrection = MathHelper.clamp(contactCorrection, -0.28D, 0.28D);
		} else if (legPlan.plantedFeet() == 0 && !legPlan.routeAvailable()) {
			Vec3d falling = getVelocity().multiply(0.90D)
					.add(0.0D, -SPIDER_FALL_SPEED, 0.0D);
			return new Vec3d(falling.x,
					Math.max(falling.y, -SPIDER_MAX_FALL_SPEED), falling.z);
		}
		Vec3d supportRecovery = spiderSupportRecovery(bodyCenter, pathNormal,
				moving ? commandDirection : travelForward);
		Vec3d result = tangent.add(supportRecovery).add(safeNormal.multiply(contactCorrection));
		if (moving && maximumSpeed > 0.0D) {
			if (Math.abs(safeNormal.y) < 0.80D) {
				result = applySpiderWallTraction(result, safeNormal, travelForward,
						throttle, maximumSpeed, bodyCenter);
			}
			result = applySpiderCommandTraction(result, safeNormal, travelForward,
					throttle, maximumSpeed, getSpiderInstalledLegCount(), legPlan.routeAvailable());
		}
		if (floating) {
			result = new Vec3d(result.x, buoyancyVelocity(getVelocity().y), result.z);
		}
		return result;
	}

	/**
	 * Foot-driven gait from the last build before wall-side steering was introduced. Keep this
	 * path isolated from the later all-direction foothold planner so a missed candidate cannot
	 * gate ordinary forward crawling.
	 */
	private void updatePreLateralSpiderFootCycle(Vec3d normal, Vec3d travelForward,
			double throttle, double turnRadians) {
		boolean moving = Math.abs(throttle) > MIN_INPUT;
		boolean turning = Math.abs(turnRadians) > 1.0E-4D;
		int activeLegCount = getSpiderInstalledLegCount();
		double speedScale = spiderCrawlSpeedScale();
		if (moving || turning || getVelocity().length() > 0.035D) {
			spiderGaitPhase = (spiderGaitPhase + (float) ((0.052D
					+ (float) Math.min(0.09D, getVelocity().length() * 0.05D
							+ Math.abs(throttle) * 0.040D
							+ Math.abs(turnRadians) / SPIDER_MAX_TURN_RADIANS * 0.045D)) * speedScale)) % 1.0F;
		}
		dataTracker.set(SPIDER_GAIT_PHASE, spiderGaitPhase);
		boolean firstTripod = spiderGaitPhase < 0.5F;
		if (firstTripod != spiderPreviousFirstTripod) {
			spiderGaitHalfSerial++;
			spiderPreviousFirstTripod = firstTripod;
		}
		int otherSupport = 0;
		int stableSupport = spiderSupportCount();
		Vec3d landedPositionSum = Vec3d.ZERO;
		int landedFeet = 0;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg != null && leg.grounded && !leg.stepping
					&& (isSpiderFirstGaitGroup(index) != firstTripod)) {
				otherSupport++;
			}
		}
		Vec3d bodyCenter = spiderBodyCenter();
		double stride = getRingDiameter() * SPIDER_STRIDE_RATIO;
		double inputSign = moving ? Math.signum(throttle) : 0.0D;
		boolean changingSurface = spiderSurfaceTransitionTicks > 0;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			int legRole = spiderLegRole(index);
			Vec3d radial = spiderPlaneRadial(normal, travelForward, index);
			if (turning && normal.y > 0.8D) {
				radial = rotateAroundAxis(radial, normal,
						turnRadians * SPIDER_TURN_FOOT_LEAD_TICKS).normalize();
			}
			double strideScale = isSpiderHighSpeedMode() ? 1.18D : 1.0D;
			Vec3d intendedBody = bodyCenter.add(travelForward.multiply(
					inputSign * (SPIDER_LEG_LOOK_AHEAD + stride * 0.22D) * strideScale));
			boolean probeLeg = isSpiderDirectionalProbeLeg(legRole, inputSign);
			SpiderSurfaceTarget stepTarget = probeLeg
					? findPreLateralSpiderProbeSurface(intendedBody, radial, normal)
					: findPreLateralSpiderLegSurface(intendedBody, radial, normal);
			if (stepTarget == null) {
				stepTarget = probeLeg
						? findPreLateralSpiderProbeSurface(bodyCenter, radial, normal)
						: findPreLateralSpiderLegSurface(bodyCenter, radial, normal);
			}
			if (stepTarget == null && moving) {
				Vec3d recoveryBody = bodyCenter.subtract(travelForward.multiply(
						inputSign * (getRingDiameter() * 0.16D + 0.18D)));
				stepTarget = findPreLateralSpiderLegSurface(recoveryBody, radial, normal);
			}
			boolean leadingLeg = moving
					&& radial.dotProduct(travelForward.multiply(inputSign)) > 0.12D;
			if (stepTarget == null && probeLeg && leadingLeg && throttle < -MIN_INPUT) {
				double descentLead = getRingDiameter() * 0.40D + 0.45D;
				Vec3d descentBody = bodyCenter.add(travelForward.multiply(inputSign * descentLead));
				stepTarget = findPreLateralSpiderDescendingLegSurface(
						descentBody, radial, normal);
				if (stepTarget != null) {
					spiderReverseCliffProbeTicks = 4;
				}
			}
			Vec3d root = bodyCenter.add(radial.multiply(
					getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO));
			Vec3d rootToFoot = leg.foot.subtract(root);
			boolean recoveryStep = stepTarget == null && moving
					&& (rootToFoot.length() > spiderLegReach(getRingLevel()) * 0.90D
							|| rootToFoot.dotProduct(radial) < SPIDER_LEG_REVERSE_LIMIT);
			Vec3d stepSurface;
			if (recoveryStep) {
				stepSurface = root.add(radial.multiply(0.58D))
						.subtract(normal.multiply(0.24D));
			} else {
				stepSurface = stepTarget == null ? null : stepTarget.point();
			}
			boolean swingingTripod = isSpiderFirstGaitGroup(index) == firstTripod;
			double forwardShare = moving
					? radial.dotProduct(travelForward.multiply(inputSign))
					: 0.0D;
			boolean urgentTerrainStep = stepSurface != null && forwardShare > 0.70D
					&& stepSurface.subtract(leg.foot).dotProduct(normal) > 0.28D;
			boolean reversedOrOverextended = rootToFoot.dotProduct(radial) < SPIDER_LEG_REVERSE_LIMIT
					|| rootToFoot.length() > spiderLegReach(getRingLevel()) * 0.94D;
			double triggerDistance = SPIDER_LEG_TRIGGER_DISTANCE
					* (turning ? 0.24D : changingSurface ? 0.22D : 0.40D);
			boolean mayStartStep = recoveryStep || urgentTerrainStep || reversedOrOverextended
					|| (swingingTripod && leg.lastStepSerial != spiderGaitHalfSerial);
			int groundedOtherSupport = stableSupport - (leg.grounded && !leg.stepping ? 1 : 0);
			int availableOtherSupport = activeLegCount < SPIDER_LEG_COUNT
					? groundedOtherSupport
					: otherSupport;
			int requiredOtherSupport = Math.min(changingSurface ? 2 : 3,
					Math.max(0, activeLegCount - 1));
			int requiredUrgentSupport = Math.min(4, activeLegCount);
			if (!leg.stepping && mayStartStep
					&& (moving || turning || changingSurface)
					&& (stepSurface != null || recoveryStep)
					&& (urgentTerrainStep
							? stableSupport >= requiredUrgentSupport
							: availableOtherSupport >= requiredOtherSupport)
					&& (urgentTerrainStep || reversedOrOverextended
							|| leg.foot.distanceTo(stepSurface) > triggerDistance)) {
				leg.lastStepSerial = spiderGaitHalfSerial;
				leg.stepping = true;
				leg.turningStep = turning;
				leg.grounded = false;
				leg.stepProgress = 0.0D;
				leg.stepStart = leg.foot;
				leg.target = stepSurface;
				leg.recoveryStep = recoveryStep;
				leg.stepBodyTarget = recoveryStep || stepTarget == null
						? bodyCenter
						: stepSurface.subtract(radial.multiply(stepTarget.radialDistance()))
								.add(normal.multiply(spiderStandHeight()));
			}
			if (leg.stepping) {
				double distance = Math.max(0.2D, leg.stepStart.distanceTo(leg.target));
				double progressDelta = leg.turningStep
						? SPIDER_TURN_STEP_PROGRESS * Math.min(1.18D, speedScale)
						: SPIDER_LEG_MOVE_SPEED * speedScale / distance;
				leg.stepProgress = Math.min(1.0D, leg.stepProgress + progressDelta);
				double t = leg.stepProgress;
				double eased = t * t * (3.0D - 2.0D * t);
				double arc = Math.sin(Math.PI * t);
				Vec3d roleSwing = spiderLegRoleSwing(legRole, travelForward, radial, inputSign, arc);
				leg.foot = keepSpiderFootOutsideSurface(
						leg.stepStart.lerp(leg.target, eased)
								.add(normal.multiply(SPIDER_LEG_LIFT
										* spiderLegLiftScale(legRole) * arc))
								.add(roleSwing),
						normal);
				if (t >= 1.0D) {
					boolean finishedRecovery = leg.recoveryStep;
					leg.foot = leg.target;
					leg.stepping = false;
					leg.turningStep = false;
					leg.recoveryStep = false;
					leg.grounded = !finishedRecovery;
					leg.supportBodyTarget = leg.stepBodyTarget;
					leg.supportNormal = normal;
					leg.stableTicks = 0;
					landedPositionSum = landedPositionSum.add(leg.foot);
					landedFeet++;
				}
			} else if (leg.grounded) {
				leg.stableTicks++;
			}
			publishSpiderFoot(index, leg.foot, bodyCenter);
		}
		if (spiderSurfaceTransitionTicks > 0) {
			spiderSurfaceTransitionTicks--;
		}
		if (landedFeet > 0) {
			playSpiderStepSound(landedPositionSum.multiply(1.0D / landedFeet), speedScale);
		}
	}

	/** Updates the later all-direction planner retained for compatibility, but no longer called. */
	private boolean updateSpiderFootCycle(Vec3d normal, Vec3d travelForward, double throttle, double turnRadians) {
		boolean moving = Math.abs(throttle) > MIN_INPUT;
		boolean turning = Math.abs(turnRadians) > 1.0E-4D;
		int activeLegCount = getSpiderInstalledLegCount();
		double speedScale = spiderCrawlSpeedScale();
		if (moving || turning || getVelocity().length() > 0.035D) {
			spiderGaitPhase = (spiderGaitPhase + (float) ((0.052D
					+ (float) Math.min(0.09D, getVelocity().length() * 0.05D
							+ Math.abs(throttle) * 0.040D
							+ Math.abs(turnRadians) / SPIDER_MAX_TURN_RADIANS * 0.045D)) * speedScale)) % 1.0F;
		}
		dataTracker.set(SPIDER_GAIT_PHASE, spiderGaitPhase);
		boolean firstTripod = spiderGaitPhase < 0.5F;
		if (firstTripod != spiderPreviousFirstTripod) {
			spiderGaitHalfSerial++;
			spiderPreviousFirstTripod = firstTripod;
		}
		int otherSupport = 0;
		int stableSupport = spiderSupportCount();
		Vec3d landedPositionSum = Vec3d.ZERO;
		int landedFeet = 0;
		boolean hasAdvancingFoothold = false;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg != null && leg.grounded && !leg.stepping
					&& (isSpiderFirstGaitGroup(index) != firstTripod)) {
				otherSupport++;
			}
		}
		Vec3d bodyCenter = spiderBodyCenter();
		int currentSurfaceSupport = spiderSupportCount(normal);
		boolean canRetireStaleSupports = activeLegCount > 2 && currentSurfaceSupport >= 2;
		int staleReleasesStarted = 0;
		double stride = getRingDiameter() * SPIDER_STRIDE_RATIO;
		double inputSign = moving ? Math.signum(throttle) : 0.0D;
		boolean changingSurface = spiderSurfaceTransitionTicks > 0;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			int legRole = spiderLegRole(index);
			Vec3d radial = spiderPlaneRadial(normal, travelForward, index);
			if (turning && normal.y > 0.8D) {
				radial = rotateAroundAxis(radial, normal,
						turnRadians * SPIDER_TURN_FOOT_LEAD_TICKS).normalize();
			}
			Vec3d travelDirection = moving
					? travelForward.multiply(inputSign)
					: Vec3d.ZERO;
			double forwardShare = moving ? radial.dotProduct(travelDirection) : 0.0D;
			boolean trailingLeg = forwardShare < -0.70D;
			double strideScale = isSpiderHighSpeedMode() ? 1.18D : 1.0D;
			Vec3d intendedBody = bodyCenter.add(travelForward.multiply(
					inputSign * (SPIDER_LEG_LOOK_AHEAD + stride * 0.22D) * strideScale));
			boolean probeLeg = isSpiderDirectionalProbeLeg(legRole, inputSign);
			SpiderSurfaceTarget stepTarget = probeLeg
					? findSpiderProbeSurface(intendedBody, radial, normal)
					: findSpiderLegSurface(intendedBody, radial, normal, trailingLeg);
			boolean leadingLeg = moving
					&& radial.dotProduct(travelForward.multiply(inputSign)) > 0.12D;
			if (stepTarget == null && probeLeg && leadingLeg && throttle < -MIN_INPUT) {
				double descentLead = getRingDiameter() * 0.40D + 0.45D;
				Vec3d descentBody = bodyCenter.add(travelForward.multiply(inputSign * descentLead));
				stepTarget = findSpiderDescendingLegSurface(descentBody, radial, normal);
				if (stepTarget != null) {
					spiderReverseCliffProbeTicks = 4;
				}
			}
			if (stepTarget == null && (moving || turning || changingSurface)) {
				stepTarget = findSpiderNearbySurface(
						intendedBody, radial, normal, travelDirection, trailingLeg);
			}
			if (stepTarget == null) {
				// Keep edge-side legs near the chassis when the look-ahead sample has already
				// crossed a wall edge. This does not alter support state or body traction.
				stepTarget = probeLeg
						? findSpiderProbeSurface(bodyCenter, radial, normal)
						: findSpiderLegSurface(bodyCenter, radial, normal, trailingLeg);
			}
			Vec3d stepSurface = stepTarget == null ? null : stepTarget.point();
			boolean staleSupport = canRetireStaleSupports
					&& leg.grounded && !leg.stepping
					&& leg.supportNormal.dotProduct(normal) < SPIDER_STALE_SUPPORT_DOT;
			boolean swingingTripod = isSpiderFirstGaitGroup(index) == firstTripod;
			Vec3d targetDelta = stepSurface == null ? Vec3d.ZERO : stepSurface.subtract(leg.foot);
			Vec3d candidateBodyTarget = stepTarget == null
					? bodyCenter
					: stepSurface.subtract(radial.multiply(stepTarget.radialDistance()))
							.add(normal.multiply(spiderStandHeight()));
			if (moving && stepTarget != null
					&& candidateBodyTarget.subtract(bodyCenter).dotProduct(travelDirection) > 0.12D) {
				hasAdvancingFoothold = true;
			}
			double trailingAdvance = targetDelta.dotProduct(travelDirection);
			double trailingDrop = -targetDelta.dotProduct(normal);
			double plantedTravelLag = -leg.foot.subtract(bodyCenter).dotProduct(travelDirection);
			boolean urgentTrailingRelease = trailingLeg && stepSurface != null
					&& trailingAdvance > 0.32D
					&& (trailingDrop > 0.28D
							|| plantedTravelLag > getRingDiameter() * 1.35D);
			boolean urgentSteepAdvance = Math.abs(normal.y) < 0.80D
					&& Math.abs(travelDirection.y) > 0.55D
					&& targetDelta.dotProduct(travelDirection) > 0.38D;
			boolean urgentTerrainStep = stepSurface != null
					&& ((forwardShare > 0.70D
							&& (targetDelta.dotProduct(normal) > 0.28D || urgentSteepAdvance))
						|| (probeLeg && changingSurface
							&& leg.foot.distanceTo(stepSurface) > 0.26D)
						|| staleSupport
						|| urgentTrailingRelease
						|| (stepTarget.pathObstructed()
							&& leg.foot.distanceTo(stepSurface) > 0.34D));
			Vec3d root = bodyCenter.add(radial.multiply(
					getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO));
			Vec3d rootToFoot = leg.foot.subtract(root);
			boolean reversedOrOverextended = rootToFoot.dotProduct(radial) < SPIDER_LEG_REVERSE_LIMIT
					|| rootToFoot.length() > spiderLegReach(getRingLevel()) * 0.94D;
			double probeHysteresis = leg.stableTicks < 5
					? SPIDER_PROBE_TARGET_HYSTERESIS * 1.30D
					: SPIDER_PROBE_TARGET_HYSTERESIS;
			boolean probeTargetJitter = probeLeg && !changingSurface && normal.y > 0.80D
					&& stepSurface != null
					&& leg.grounded && !leg.stepping && !reversedOrOverextended
					&& Math.abs(targetDelta.dotProduct(normal)) < SPIDER_PROBE_HEIGHT_HYSTERESIS
					&& projectOntoPlane(targetDelta, normal).length() < probeHysteresis;
			double triggerDistance = SPIDER_LEG_TRIGGER_DISTANCE
					* (turning ? 0.24D : changingSurface ? 0.22D : 0.40D);
			boolean mayStartStep = urgentTerrainStep || reversedOrOverextended
					|| (swingingTripod && leg.lastStepSerial != spiderGaitHalfSerial);
			int groundedOtherSupport = stableSupport - (leg.grounded && !leg.stepping ? 1 : 0);
			int availableOtherSupport = activeLegCount < SPIDER_LEG_COUNT
					? groundedOtherSupport
					: otherSupport;
			int requiredOtherSupport = Math.min(changingSurface ? 2 : 3,
					Math.max(0, activeLegCount - 1));
			int requiredUrgentSupport = Math.min(4, activeLegCount);
			if (!leg.stepping && mayStartStep
					&& (moving || turning || changingSurface)
					&& stepSurface != null
					&& (!staleSupport || staleReleasesStarted == 0)
					&& (urgentTerrainStep
							? stableSupport >= requiredUrgentSupport
							: availableOtherSupport >= requiredOtherSupport)
					&& (urgentTerrainStep || reversedOrOverextended
							|| (!probeTargetJitter
									&& leg.foot.distanceTo(stepSurface) > triggerDistance))) {
				leg.lastStepSerial = spiderGaitHalfSerial;
				leg.stepping = true;
				leg.turningStep = turning;
				leg.grounded = false;
				leg.stepProgress = 0.0D;
				leg.stepStart = leg.foot;
				leg.target = stepSurface;
				leg.stepBodyTarget = candidateBodyTarget;
				if (staleSupport) {
					staleReleasesStarted++;
				}
			}
			if (leg.stepping) {
				double distance = Math.max(0.2D, leg.stepStart.distanceTo(leg.target));
				double progressDelta = leg.turningStep
						? SPIDER_TURN_STEP_PROGRESS * Math.min(1.18D, speedScale)
						: SPIDER_LEG_MOVE_SPEED * speedScale / distance;
				leg.stepProgress = Math.min(1.0D, leg.stepProgress + progressDelta);
				double t = leg.stepProgress;
				double eased = t * t * (3.0D - 2.0D * t);
				double arc = Math.sin(Math.PI * t);
				Vec3d roleSwing = spiderLegRoleSwing(legRole, travelForward, radial, inputSign, arc);
				leg.foot = keepSpiderFootOutsideSurface(
						leg.stepStart.lerp(leg.target, eased)
								.add(normal.multiply(SPIDER_LEG_LIFT * spiderLegLiftScale(legRole) * arc))
								.add(roleSwing),
						normal);
				if (t >= 1.0D) {
					leg.foot = leg.target;
					leg.stepping = false;
					leg.turningStep = false;
					leg.grounded = true;
					leg.supportBodyTarget = leg.stepBodyTarget;
					leg.supportNormal = normal;
					leg.stableTicks = 0;
					landedPositionSum = landedPositionSum.add(leg.foot);
					landedFeet++;
				}
			} else if (leg.grounded) {
				leg.stableTicks++;
			}
			publishSpiderFoot(index, leg.foot, bodyCenter);
		}
		if (spiderSurfaceTransitionTicks > 0) {
			spiderSurfaceTransitionTicks--;
		}
		if (landedFeet > 0) {
			playSpiderStepSound(landedPositionSum.multiply(1.0D / landedFeet), speedScale);
		}
		return hasAdvancingFoothold;
	}

	/** Sparse builds alternate the legs that actually exist instead of relying on fixed index parity. */
	private boolean isSpiderFirstGaitGroup(int legIndex) {
		int ordinal = 0;
		for (int installedIndex = 0; installedIndex < SPIDER_LEG_COUNT; installedIndex++) {
			if (!hasFunctionalSpiderLeg(installedIndex)) {
				continue;
			}
			if (installedIndex == legIndex) {
				return (ordinal & 1) == 0;
			}
			ordinal++;
		}
		return (legIndex & 1) == 0;
	}

	private boolean hasSpiderSteppingRole(int role) {
		if (role < 0) {
			return false;
		}
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg != null && leg.stepping && spiderLegRole(index) == role) {
				return true;
			}
		}
		return false;
	}

	private boolean hasSpiderCandidateForRole(List<SpiderStepCandidate> candidates, int role) {
		for (SpiderStepCandidate candidate : candidates) {
			if (spiderLegRole(candidate.legIndex()) == role) {
				return true;
			}
		}
		return false;
	}

	private int findInstalledSpiderGaitRole(int startingRole, int directionSign) {
		int role = startingRole;
		for (int attempt = 0; attempt < 4; attempt++) {
			for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
				if (hasFunctionalSpiderLeg(index) && spiderLegRole(index) == role) {
					return role;
				}
			}
			role = nextSpiderGaitRole(role, directionSign);
		}
		return -1;
	}

	private static int nextSpiderGaitRole(int role, int directionSign) {
		return directionSign >= 0 ? (role + 1) & 3 : (role + 3) & 3;
	}

	/** Roles advance from front to rear: pull/probe, walk, support, then push. */
	private static int spiderLegRole(int index) {
		return switch (index) {
			case 5, 6 -> 0;
			case 4, 7 -> 1;
			case 0, 3 -> 2;
			default -> 3;
		};
	}

	private static boolean isSpiderDirectionalProbeLeg(int role, double inputSign) {
		return inputSign >= 0.0D ? role == 0 : role == 3;
	}

	private static double spiderLegLiftScale(int role) {
		return switch (role) {
			case 0 -> 1.08D;
			case 2 -> 0.95D;
			case 3 -> 0.90D;
			default -> 1.0D;
		};
	}

	private static Vec3d spiderLegRoleSwing(int role, Vec3d travelForward, Vec3d radial,
			double inputSign, double arc) {
		if (Math.abs(inputSign) < MIN_INPUT) {
			return Vec3d.ZERO;
		}
		return switch (role) {
			case 0 -> travelForward.multiply(inputSign * arc * 0.12D);
			case 1 -> travelForward.multiply(inputSign * arc * 0.032D);
			case 2 -> radial.multiply(-arc * 0.035D)
					.add(travelForward.multiply(-inputSign * arc * 0.025D));
			case 3 -> travelForward.multiply(-inputSign * arc * 0.085D);
			default -> Vec3d.ZERO;
		};
	}

	private Vec3d keepSpiderFootOutsideSurface(Vec3d foot, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d surface = findSpiderSurface(foot, safeNormal, 1.20D, 0.60D);
		if (surface == null) {
			return foot;
		}
		double clearance = foot.subtract(surface).dotProduct(safeNormal);
		return clearance < 0.10D && foot.distanceTo(surface) < 2.2D
				? foot.add(safeNormal.multiply(Math.min(0.16D, 0.10D - clearance)))
				: foot;
	}

	private int spiderSupportCount() {
		int count = 0;
		for (SpiderLegState leg : spiderLegs) {
			if (leg != null && leg.grounded && !leg.stepping) {
				count++;
			}
		}
		return count;
	}

	private int spiderMotionSupportCount() {
		int count = 0;
		for (SpiderLegState leg : spiderLegs) {
			if (leg != null && (leg.grounded || leg.stepping)) {
				count++;
			}
		}
		return count;
	}

	private int spiderSupportCount(Vec3d normal) {
		int count = 0;
		Vec3d safeNormal = normal.normalize();
		for (SpiderLegState leg : spiderLegs) {
			if (leg != null && leg.grounded && !leg.stepping
					&& leg.supportNormal.dotProduct(safeNormal) > 0.72D) {
				count++;
			}
		}
		return count;
	}

	/** Body tilt paired with the pre-lateral gait. */
	private void updatePreLateralSpiderBodyNormal(Vec3d fallbackNormal) {
		Vec3d safeFallback = fallbackNormal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: fallbackNormal.normalize();
		Vec3d bodyCenter = spiderBodyCenter();
		List<Vec3d> supports = new ArrayList<>();
		Vec3d centroid = Vec3d.ZERO;
		Vec3d supportBias = Vec3d.ZERO;
		SpiderLegState soleSupport = null;
		for (SpiderLegState leg : spiderLegs) {
			if (leg != null && leg.grounded && !leg.stepping) {
				supports.add(leg.foot);
				soleSupport = leg;
				centroid = centroid.add(leg.foot);
				Vec3d projected = projectOntoPlane(
						leg.foot.subtract(bodyCenter), safeFallback);
				if (projected.lengthSquared() > 0.01D) {
					supportBias = supportBias.add(projected.normalize());
				}
			}
		}
		Vec3d desired = safeFallback;
		if (supports.size() >= 3) {
			centroid = centroid.multiply(1.0D / supports.size());
			Vec3d areaNormal = Vec3d.ZERO;
			for (int index = 0; index < supports.size(); index++) {
				Vec3d current = supports.get(index).subtract(centroid);
				Vec3d next = supports.get((index + 1) % supports.size()).subtract(centroid);
				areaNormal = areaNormal.add(current.crossProduct(next));
			}
			if (areaNormal.lengthSquared() > 1.0E-6D) {
				desired = areaNormal.normalize();
				if (desired.dotProduct(safeFallback) < 0.0D) {
					desired = desired.negate();
				}
			}
		} else if (supports.size() == 1 && soleSupport != null
				&& getSpiderInstalledLegCount() == 1) {
			desired = singleSpiderLegBodyNormal(
					soleSupport.foot, bodyCenter, safeFallback);
		} else if (getSpiderInstalledLegCount() <= 2
				&& !supports.isEmpty() && supportBias.lengthSquared() > 0.01D) {
			double imbalance = MathHelper.clamp(
					supportBias.length() / supports.size(), 0.0D, 1.0D);
			double scarcity = (3.0D - supports.size()) / 2.0D;
			double lean = 1.12D * imbalance * scarcity;
			desired = safeFallback.subtract(
					supportBias.normalize().multiply(lean)).normalize();
		}
		Vec3d current = trackedSpiderBodyNormal();
		if (supports.isEmpty() && getSpiderInstalledLegCount() < 3) {
			desired = current;
		}
		double maximumStep = spiderSurfaceTransitionTicks > 0
				? SPIDER_TRANSITION_NORMAL_STEP
				: SPIDER_BODY_NORMAL_STEP;
		setSpiderBodyNormal(stepSpiderBodyNormal(current, desired, maximumStep));
	}

	/** Blends trustworthy contact normals with the planted-foot plane without allowing inversion. */
	private void updateSpiderBodyNormal(Vec3d fallbackNormal) {
		Vec3d safeFallback = fallbackNormal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: fallbackNormal.normalize();
		Vec3d bodyCenter = spiderBodyCenter();
		List<Vec3d> supports = new ArrayList<>();
		Vec3d normalSum = safeFallback.multiply(2.0D);
		Vec3d sparseSupportBias = Vec3d.ZERO;
		SpiderLegState soleSupport = null;
		boolean sparseBuild = getSpiderInstalledLegCount() <= 2;
		double maximumReach = spiderLegMaximumReach(getRingLevel()) * 1.08D;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null || !leg.grounded || leg.stepping
					|| leg.foot.distanceTo(bodyCenter) > maximumReach + spiderStandHeight()) {
				continue;
			}
			Vec3d supportNormal = leg.supportNormal.lengthSquared() < 0.5D
					? safeFallback
					: leg.supportNormal.normalize();
			double alignment = supportNormal.dotProduct(safeFallback);
			if (alignment < -0.15D) {
				continue;
			}
			supports.add(leg.foot);
			soleSupport = leg;
			normalSum = normalSum.add(supportNormal.multiply(
					0.62D + Math.max(0.0D, alignment) * 0.50D));
			Vec3d projected = projectOntoPlane(leg.foot.subtract(bodyCenter), safeFallback);
			if (projected.lengthSquared() > 0.01D) {
				sparseSupportBias = sparseSupportBias.add(projected.normalize());
			}
		}
		Vec3d desired = normalSum.lengthSquared() < 0.25D ? safeFallback : normalSum.normalize();
		if (supports.size() >= 3) {
			Vec3d fittedNormal = fitSpiderSupportPlaneNormal(supports, safeFallback);
			if (fittedNormal.dotProduct(desired) > 0.12D) {
				double terrainAngle = Math.acos(MathHelper.clamp(
						fittedNormal.dotProduct(safeFallback), -1.0D, 1.0D));
				double terrainWeight = MathHelper.clamp(
						0.46D + terrainAngle / (Math.PI * 0.5D) * 0.24D, 0.46D, 0.70D);
				Vec3d blended = desired.multiply(1.0D - terrainWeight)
						.add(fittedNormal.multiply(terrainWeight));
				if (blended.lengthSquared() > 0.25D) {
					desired = blended.normalize();
				}
			}
		} else if (supports.size() == 1 && soleSupport != null
				&& getSpiderInstalledLegCount() == 1) {
			desired = singleSpiderLegBodyNormal(soleSupport.foot, bodyCenter, safeFallback);
		} else if (getSpiderInstalledLegCount() <= 2
				&& !supports.isEmpty() && sparseSupportBias.lengthSquared() > 0.01D) {
			// A sparse chassis is propped up on the fitted side while its unsupported edge drags.
			double imbalance = MathHelper.clamp(
					sparseSupportBias.length() / supports.size(), 0.0D, 1.0D);
			double scarcity = (3.0D - supports.size()) / 2.0D;
			double lean = 1.12D * imbalance * scarcity;
			desired = safeFallback.subtract(
					sparseSupportBias.normalize().multiply(lean)).normalize();
		}
		if (!supports.isEmpty() && spiderExplorationNormalTarget.lengthSquared() > 0.25D) {
			Vec3d explorationNormal = spiderExplorationNormalTarget.normalize();
			if (explorationNormal.dotProduct(desired) > -0.15D) {
				Vec3d blended = desired.multiply(0.72D).add(explorationNormal.multiply(0.28D));
				if (blended.lengthSquared() > 0.25D) {
					desired = blended.normalize();
				}
			}
		}
		Vec3d current = trackedSpiderBodyNormal();
		if (supports.isEmpty() && getSpiderInstalledLegCount() < 3) {
			desired = current;
		}
		if (desired.dotProduct(current) < -0.20D) {
			Vec3d guarded = desired.add(safeFallback.multiply(1.35D));
			desired = guarded.lengthSquared() < 0.25D ? safeFallback : guarded.normalize();
		}
		double maximumStep = spiderSurfaceTransitionTicks > 0
				? SPIDER_TRANSITION_NORMAL_STEP
				: SPIDER_BODY_NORMAL_STEP;
		setSpiderBodyNormal(stepSpiderBodyNormal(current, desired, maximumStep));
	}

	/** Fits chassis pitch and roll from real foot heights instead of averaging block-face normals. */
	private Vec3d fitSpiderSupportPlaneNormal(List<Vec3d> supports, Vec3d fallbackNormal) {
		Vec3d forward = projectOntoPlane(getForwardVector(), fallbackNormal);
		if (forward.lengthSquared() < 0.01D) {
			forward = spiderTravelForward(fallbackNormal, getBodyHeading());
		}
		if (forward.lengthSquared() < 0.01D) {
			return fallbackNormal;
		}
		forward = forward.normalize();
		Vec3d side = fallbackNormal.crossProduct(forward);
		if (side.lengthSquared() < 0.01D) {
			return fallbackNormal;
		}
		side = side.normalize();

		double meanX = 0.0D;
		double meanY = 0.0D;
		double meanZ = 0.0D;
		for (Vec3d support : supports) {
			meanX += support.dotProduct(side);
			meanY += support.dotProduct(forward);
			meanZ += support.dotProduct(fallbackNormal);
		}
		double inverseCount = 1.0D / supports.size();
		meanX *= inverseCount;
		meanY *= inverseCount;
		meanZ *= inverseCount;
		double xx = 0.0D;
		double xy = 0.0D;
		double yy = 0.0D;
		double xz = 0.0D;
		double yz = 0.0D;
		for (Vec3d support : supports) {
			double x = support.dotProduct(side) - meanX;
			double y = support.dotProduct(forward) - meanY;
			double z = support.dotProduct(fallbackNormal) - meanZ;
			xx += x * x;
			xy += x * y;
			yy += y * y;
			xz += x * z;
			yz += y * z;
		}
		double determinant = xx * yy - xy * xy;
		if (determinant < 1.0E-5D) {
			return fallbackNormal;
		}
		double sideSlope = (xz * yy - yz * xy) / determinant;
		double forwardSlope = (yz * xx - xz * xy) / determinant;
		double slope = Math.sqrt(sideSlope * sideSlope + forwardSlope * forwardSlope);
		if (slope > 1.35D) {
			double scale = 1.35D / slope;
			sideSlope *= scale;
			forwardSlope *= scale;
		}
		Vec3d fitted = fallbackNormal
				.subtract(side.multiply(sideSlope))
				.subtract(forward.multiply(forwardSlope));
		return fitted.lengthSquared() < 0.25D ? fallbackNormal : fitted.normalize();
	}

	/** Fits the one-legged body between its real foot height and the unsupported edge contact. */
	private Vec3d singleSpiderLegBodyNormal(Vec3d foot, Vec3d bodyCenter, Vec3d surfaceNormal) {
		Vec3d supportOffset = projectOntoPlane(foot.subtract(bodyCenter), surfaceNormal);
		if (supportOffset.lengthSquared() < 0.01D) {
			return surfaceNormal;
		}
		Vec3d supportDirection = supportOffset.normalize();
		double radius = getRingDiameter() * 0.5D;
		Vec3d nominalSurfaceCenter = bodyCenter.subtract(surfaceNormal.multiply(spiderStandHeight()));
		Vec3d oppositeSample = nominalSurfaceCenter.subtract(supportDirection.multiply(radius * 0.86D));
		Vec3d oppositeSurface = findSpiderSurface(oppositeSample, surfaceNormal, 1.45D, 2.10D);
		if (oppositeSurface == null) {
			oppositeSurface = oppositeSample;
		}
		double terrainHeightDifference = foot.subtract(oppositeSurface).dotProduct(surfaceNormal);
		double braceLift = radius * 0.72D;
		double edgeSlope = MathHelper.clamp(
				(terrainHeightDifference + braceLift) / Math.max(0.5D, radius * 2.0D),
				-0.82D, 0.82D);
		return surfaceNormal.subtract(supportDirection.multiply(edgeSlope)).normalize();
	}

	/** Limits each normal update so an underside transition cannot cross an antipodal lerp singularity. */
	private Vec3d stepSpiderBodyNormal(Vec3d current, Vec3d desired, double maximumStep) {
		double dot = MathHelper.clamp(current.dotProduct(desired), -1.0D, 1.0D);
		double angle = Math.acos(dot);
		if (angle <= maximumStep || angle < 1.0E-5D) {
			return desired;
		}
		Vec3d axis = current.crossProduct(desired);
		if (axis.lengthSquared() < 1.0E-6D) {
			Vec3d travel = projectOntoPlane(getForwardVector(), current);
			if (travel.lengthSquared() < 1.0E-6D) {
				travel = projectOntoPlane(getBodyHeading(), current);
			}
			axis = current.crossProduct(travel);
		}
		if (axis.lengthSquared() < 1.0E-6D) {
			axis = current.crossProduct(Math.abs(current.y) < 0.9D
					? Vec3d.of(Direction.UP.getVector())
					: new Vec3d(1.0D, 0.0D, 0.0D));
		}
		return rotateAroundAxis(current, axis.normalize(), maximumStep).normalize();
	}

	private Vec3d trackedSpiderBodyNormal() {
		Vector3f vector = dataTracker.get(SPIDER_BODY_NORMAL);
		Vec3d normal = new Vec3d(vector.x(), vector.y(), vector.z());
		return normal.lengthSquared() < 0.5D ? Vec3d.of(Direction.UP.getVector()) : normal.normalize();
	}

	private void setSpiderBodyNormal(Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		dataTracker.set(SPIDER_BODY_NORMAL,
				new Vector3f((float) safeNormal.x, (float) safeNormal.y, (float) safeNormal.z));
	}

	private void playSpiderStepSound(Vec3d position, double speedScale) {
		if (spiderStepSoundCooldown > 0) {
			return;
		}
		float speedPitch = (float) MathHelper.clamp((speedScale - 1.0D) * 0.16D, 0.0D, 0.18D);
		getWorld().playSound(null, position.x, position.y, position.z, SoundEvents.ENTITY_SPIDER_STEP,
				SoundCategory.NEUTRAL, 0.13F, 0.72F + speedPitch + random.nextFloat() * 0.08F);
		getWorld().playSound(null, position.x, position.y, position.z, SoundEvents.BLOCK_CHAIN_STEP,
				SoundCategory.NEUTRAL, 0.48F, 0.68F + speedPitch + random.nextFloat() * 0.10F);
		getWorld().playSound(null, position.x, position.y, position.z, SoundEvents.BLOCK_METAL_STEP,
				SoundCategory.NEUTRAL, 0.28F, 0.74F + speedPitch + random.nextFloat() * 0.08F);
		spiderStepSoundCooldown = isSpiderHighSpeedMode() ? 2 : 3;
	}

	/** Support target used by the pre-lateral gait. */
	private Vec3d spiderMotionBodyTarget() {
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d sum = Vec3d.ZERO;
		double totalWeight = 0.0D;
		boolean sparsePullingGait = getSpiderInstalledLegCount() <= 2;
		for (SpiderLegState leg : spiderLegs) {
			if (leg == null) {
				continue;
			}
			if (leg.grounded && !leg.stepping) {
				sum = sum.add(leg.supportBodyTarget);
				totalWeight += 1.0D;
			} else if (leg.stepping) {
				double weight;
				Vec3d steppingTarget;
				if (sparsePullingGait) {
					weight = 0.24D;
					steppingTarget = leg.supportBodyTarget;
				} else {
					double t = leg.stepProgress;
					double eased = t * t * (3.0D - 2.0D * t);
					weight = 0.35D + eased * 0.65D;
					steppingTarget = leg.supportBodyTarget.lerp(leg.stepBodyTarget, eased);
				}
				sum = sum.add(steppingTarget.multiply(weight));
				totalWeight += weight;
			}
		}
		return totalWeight < 0.001D ? bodyCenter : sum.multiply(1.0D / totalWeight);
	}

	private Vec3d spiderMotionBodyTarget(Vec3d normal) {
		Vec3d bodyCenter = spiderBodyCenter();
		Vec3d sum = Vec3d.ZERO;
		double totalWeight = 0.0D;
		boolean sparsePullingGait = getSpiderInstalledLegCount() <= 2;
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		boolean ignoreStaleSupports = !sparsePullingGait && spiderSupportCount(safeNormal) >= 2;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg == null) {
				continue;
			}
			if (ignoreStaleSupports
					&& leg.supportNormal.dotProduct(safeNormal) < SPIDER_STALE_SUPPORT_DOT) {
				continue;
			}
			if (leg.grounded && !leg.stepping) {
				sum = sum.add(leg.supportBodyTarget);
				totalWeight += 1.0D;
			} else if (leg.stepping) {
				double weight;
				Vec3d steppingTarget;
				if (sparsePullingGait) {
					// One or two legs must plant before they can drag the unsupported chassis.
					weight = 0.24D;
					steppingTarget = leg.supportBodyTarget;
				} else {
					double t = leg.stepProgress;
					double eased = t * t * (3.0D - 2.0D * t);
					weight = 0.35D + eased * 0.65D;
					steppingTarget = leg.supportBodyTarget.lerp(leg.stepBodyTarget, eased);
				}
				sum = sum.add(steppingTarget.multiply(weight));
				totalWeight += weight;
			}
		}
		if (totalWeight < 0.001D) {
			return bodyCenter;
		}
		return sum.multiply(1.0D / totalWeight);
	}

	private boolean isSpiderHighSpeedMode() {
		return getVariant().powered() && isHighGear();
	}

	private double spiderCrawlSpeedScale() {
		double legFraction = getSpiderInstalledLegCount() / (double) SPIDER_LEG_COUNT;
		double gaitEfficiency = 0.55D + 0.45D * Math.sqrt(Math.max(0.0D, legFraction));
		return (isSpiderHighSpeedMode() ? SPIDER_HIGH_SPEED_SCALE : SPIDER_LOW_SPEED_SCALE)
				* gaitEfficiency;
	}

	private double spiderMaximumSpeed() {
		double legFraction = getSpiderInstalledLegCount() / (double) SPIDER_LEG_COUNT;
		double tractionEfficiency = 0.28D + 0.72D * Math.sqrt(Math.max(0.0D, legFraction));
		return (isSpiderHighSpeedMode() ? SPIDER_HIGH_MAX_SPEED : SPIDER_LOW_MAX_SPEED)
				* tractionEfficiency;
	}

	private Vec3d findSpiderSurface(Vec3d sample, Vec3d normal, double outwardSearch, double inwardSearch) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d start = sample.add(safeNormal.multiply(Math.max(0.05D, outwardSearch)));
		Vec3d end = sample.subtract(safeNormal.multiply(Math.max(0.05D, inwardSearch)));
		BlockHitResult hit = getWorld().raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		if (hit.getType() != HitResult.Type.MISS
				&& Vec3d.of(hit.getSide().getVector()).dotProduct(safeNormal) > 0.55D) {
			return hit.getPos();
		}
		if (safeNormal.y > 0.8D && Double.isFinite(fluidSurfaceY)
				&& Math.abs(sample.y - fluidSurfaceY) < 2.5D) {
			return new Vec3d(sample.x, fluidSurfaceY - SPIDER_WATER_FOOT_DEPTH, sample.z);
		}
		return null;
	}

	/**
	 * Gives the movement-leading pair a probe-like endpoint. It tries level, raised, then lowered
	 * footholds and rejects every target that would fold the endpoint behind its own leg root.
	 */
	private SpiderSurfaceTarget findSpiderProbeSurface(Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double nominalReach = spiderLegReach(getRingLevel());
		double maximumReach = spiderLegMaximumReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = spiderBodyCenter().add(radial.multiply(rootRadius));
		double probeStep = Math.max(0.62D, getRingDiameter() * 0.23D);
		double[] heightPriority = {0.0D, probeStep, probeStep * 2.15D, probeStep * 3.35D,
				-probeStep * 0.82D, -probeStep * 1.45D};
		// Keep distance as the outer priority: a raised foothold ahead is preferable to a
		// level foothold beside the chassis when the leading legs meet a slope or wall.
		for (double scale : new double[]{0.72D, 0.58D, 0.46D, 0.34D, 0.24D, 0.16D, 0.10D}) {
			for (double heightOffset : heightPriority) {
				double radialDistance = nominalReach * scale;
				Vec3d sample = bodyTarget.add(radial.multiply(radialDistance))
						.subtract(safeNormal.multiply(spiderStandHeight()))
						.add(safeNormal.multiply(heightOffset));
				Vec3d surface = findSpiderSurface(sample, safeNormal,
						Math.max(0.72D, probeStep * 0.78D),
						Math.max(0.90D, probeStep * 0.92D));
				if (surface == null) {
					continue;
				}
				SpiderSurfaceTarget target = validateSpiderLegTarget(
						bodyTarget, root, radial, safeNormal, surface, radialDistance, maximumReach, false);
				if (target != null) {
					return target;
				}
			}
		}
		return null;
	}

	/** Lets the extended legs telescope inward when the full extension misses a wall. */
	private SpiderSurfaceTarget findSpiderLegSurface(Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		return findSpiderLegSurface(bodyTarget, radial, normal, false);
	}

	private SpiderSurfaceTarget findSpiderLegSurface(Vec3d bodyTarget, Vec3d radial, Vec3d normal,
			boolean preferCompactFoothold) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double nominalReach = spiderLegReach(getRingLevel());
		double maximumReach = spiderLegMaximumReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = spiderBodyCenter().add(radial.multiply(rootRadius));
		double[] radialScales = preferCompactFoothold
				? new double[]{0.72D, 0.60D, 0.84D, 0.50D, 1.0D, 0.34D, 0.22D, 0.14D, 0.10D}
				: new double[]{1.0D, 0.72D, 0.50D, 0.34D, 0.22D, 0.14D, 0.10D};
		for (double scale : radialScales) {
			double radialDistance = nominalReach * scale;
			Vec3d sample = bodyTarget.add(radial.multiply(radialDistance))
					.subtract(safeNormal.multiply(spiderStandHeight()));
			Vec3d surface = findSpiderSurface(sample, safeNormal, 2.05D, 1.35D);
			if (surface != null) {
				SpiderSurfaceTarget target = validateSpiderLegTarget(
						bodyTarget, root, radial, safeNormal, surface, radialDistance, maximumReach, true);
				if (target != null) {
					return target;
				}
			}
		}
		return null;
	}

	private SpiderSurfaceTarget findPreLateralSpiderProbeSurface(
			Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double maximumReach = spiderLegReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = spiderBodyCenter().add(radial.multiply(rootRadius));
		double probeStep = Math.max(0.62D, getRingDiameter() * 0.23D);
		double[] heightPriority = {0.0D, probeStep, probeStep * 1.75D,
				-probeStep * 0.82D, -probeStep * 1.45D};
		for (double heightOffset : heightPriority) {
			for (double scale : new double[]{0.50D, 0.38D, 0.28D, 0.20D, 0.14D, 0.10D}) {
				double radialDistance = maximumReach * scale;
				Vec3d sample = bodyTarget.add(radial.multiply(radialDistance))
						.subtract(safeNormal.multiply(spiderStandHeight()))
						.add(safeNormal.multiply(heightOffset));
				Vec3d surface = findSpiderSurface(sample, safeNormal,
						Math.max(0.72D, probeStep * 0.78D),
						Math.max(0.90D, probeStep * 0.92D));
				if (surface == null) {
					continue;
				}
				Vec3d rootToSurface = surface.subtract(root);
				if (rootToSurface.length() <= maximumReach * 0.985D
						&& rootToSurface.dotProduct(radial) >= SPIDER_LEG_REVERSE_LIMIT) {
					return new SpiderSurfaceTarget(surface, radialDistance);
				}
			}
		}
		return null;
	}

	private SpiderSurfaceTarget findPreLateralSpiderLegSurface(
			Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double maximumReach = spiderLegReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d currentRoot = spiderBodyCenter().add(radial.multiply(rootRadius));
		double minimumRadialDistance = rootRadius + SPIDER_LEG_REVERSE_LIMIT;
		for (double scale : new double[]{1.0D, 0.82D, 0.68D, 0.56D, 0.46D,
				0.38D, 0.32D, 0.27D, 0.23D}) {
			double radialDistance = maximumReach * scale;
			if (radialDistance < minimumRadialDistance) {
				continue;
			}
			Vec3d sample = bodyTarget.add(radial.multiply(radialDistance))
					.subtract(safeNormal.multiply(spiderStandHeight()));
			Vec3d surface = findSpiderSurface(sample, safeNormal, 2.05D, 1.35D);
			if (surface == null) {
				continue;
			}
			Vec3d rootToSurface = surface.subtract(currentRoot);
			if (rootToSurface.length() <= maximumReach * 0.985D
					&& rootToSurface.dotProduct(radial) >= SPIDER_LEG_REVERSE_LIMIT) {
				return new SpiderSurfaceTarget(surface, radialDistance);
			}
		}
		return null;
	}

	private SpiderSurfaceTarget findPreLateralSpiderDescendingLegSurface(
			Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		if (safeNormal.y < 0.80D) {
			return null;
		}
		double maximumReach = spiderLegReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d currentRoot = spiderBodyCenter().add(radial.multiply(rootRadius));
		double probeDepth = Math.max(4.5D,
				maximumReach * SPIDER_CLIFF_PROBE_DEPTH_RATIO);
		for (double scale : new double[]{0.05D, 0.09D, 0.14D, 0.20D, 0.28D, 0.38D, 0.52D}) {
			double radialDistance = maximumReach * scale;
			Vec3d column = bodyTarget.add(radial.multiply(radialDistance));
			Vec3d start = column.subtract(safeNormal.multiply(
					Math.max(0.35D, spiderStandHeight() - 1.0D)));
			Vec3d end = column.subtract(safeNormal.multiply(spiderStandHeight() + probeDepth));
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start, end,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() != HitResult.Type.MISS
					&& Vec3d.of(hit.getSide().getVector()).dotProduct(safeNormal) > 0.55D
					&& currentRoot.distanceTo(hit.getPos())
							<= maximumReach * SPIDER_CLIFF_REACH_RATIO) {
				return new SpiderSurfaceTarget(hit.getPos(), radialDistance);
			}
		}
		return null;
	}

	/**
	 * Searches the current surface plane around a missed nominal foothold. This is intentionally a
	 * fallback: ordinary gait rays stay cheap, while wall and cliff legs may still reach above,
	 * below, or beside their radial line instead of remaining attached to an obsolete face.
	 */
	private SpiderSurfaceTarget findSpiderNearbySurface(Vec3d bodyTarget, Vec3d radial, Vec3d normal,
			Vec3d travelDirection, boolean preferCompactFoothold) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		Vec3d surfaceTravel = projectOntoPlane(travelDirection, safeNormal);
		if (surfaceTravel.lengthSquared() < 0.01D) {
			surfaceTravel = projectOntoPlane(getForwardVector(), safeNormal);
		}
		if (surfaceTravel.lengthSquared() < 0.01D) {
			surfaceTravel = radial;
		}
		surfaceTravel = surfaceTravel.normalize();
		Vec3d surfaceSide = safeNormal.crossProduct(surfaceTravel);
		if (surfaceSide.lengthSquared() < 0.01D) {
			return null;
		}
		surfaceSide = surfaceSide.normalize();
		double sideSign = radial.dotProduct(surfaceSide) >= 0.0D ? 1.0D : -1.0D;
		Vec3d worldVertical = projectOntoPlane(Vec3d.of(Direction.UP.getVector()), safeNormal);
		if (worldVertical.lengthSquared() >= 0.01D) {
			worldVertical = worldVertical.normalize();
		} else {
			worldVertical = Vec3d.ZERO;
		}

		double searchStep = Math.max(0.78D, getRingDiameter() * 0.38D);
		Vec3d[] offsets = {
				surfaceTravel.multiply(searchStep),
				surfaceSide.multiply(sideSign * searchStep),
				surfaceSide.multiply(-sideSign * searchStep),
				worldVertical.multiply(searchStep),
				worldVertical.multiply(-searchStep),
				surfaceTravel.add(surfaceSide.multiply(sideSign * 0.62D)).multiply(searchStep * 1.55D),
				surfaceTravel.add(surfaceSide.multiply(-sideSign * 0.62D)).multiply(searchStep * 1.55D),
				surfaceTravel.multiply(-searchStep * 0.62D)
		};
		for (Vec3d offset : offsets) {
			if (offset.lengthSquared() < 0.04D) {
				continue;
			}
			SpiderSurfaceTarget target = findSpiderNearbySurfaceColumn(
					bodyTarget.add(offset), radial, safeNormal, preferCompactFoothold);
			if (target != null) {
				return target;
			}
		}
		return null;
	}

	private SpiderSurfaceTarget findSpiderNearbySurfaceColumn(Vec3d bodyTarget, Vec3d radial,
			Vec3d normal, boolean preferCompactFoothold) {
		double nominalReach = spiderLegReach(getRingLevel());
		double maximumReach = spiderLegMaximumReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d root = spiderBodyCenter().add(radial.multiply(rootRadius));
		double[] radialScales = preferCompactFoothold
				? new double[]{0.54D, 0.36D, 0.72D}
				: new double[]{0.72D, 0.52D, 0.34D};
		for (double scale : radialScales) {
			double radialDistance = nominalReach * scale;
			Vec3d sample = bodyTarget.add(radial.multiply(radialDistance))
					.subtract(normal.multiply(spiderStandHeight()));
			Vec3d surface = findSpiderSurface(sample, normal, 1.55D, 1.30D);
			if (surface == null) {
				continue;
			}
			SpiderSurfaceTarget target = validateSpiderLegTarget(
					bodyTarget, root, radial, normal, surface, radialDistance, maximumReach, true);
			if (target != null) {
				return target;
			}
		}
		return null;
	}

	/** Rejects unreachable feet and lifts an obstructed leg path onto the blocking terrain. */
	private SpiderSurfaceTarget validateSpiderLegTarget(Vec3d bodyTarget, Vec3d root, Vec3d radial,
			Vec3d normal, Vec3d surface, double radialDistance, double maximumReach,
			boolean resolvePathObstruction) {
		Vec3d adjustedSurface = resolvePathObstruction
				? liftSpiderFootOntoPathObstacle(root, surface, normal)
				: surface;
		if (adjustedSurface == null) {
			return null;
		}
		double adjustmentDistanceSquared = adjustedSurface.squaredDistanceTo(surface);
		Vec3d rootToSurface = adjustedSurface.subtract(root);
		if (rootToSurface.length() > maximumReach * 0.985D
				|| rootToSurface.dotProduct(radial) < SPIDER_LEG_REVERSE_LIMIT) {
			return null;
		}
		if (adjustmentDistanceSquared > 1.0E-4D) {
			Vec3d supportOrigin = bodyTarget.subtract(normal.multiply(spiderStandHeight()));
			radialDistance = MathHelper.clamp(
					adjustedSurface.subtract(supportOrigin).dotProduct(radial),
					spiderLegReach(getRingLevel()) * 0.08D, maximumReach);
		}
		return new SpiderSurfaceTarget(adjustedSurface, radialDistance,
				adjustmentDistanceSquared > 0.09D);
	}

	private Vec3d liftSpiderFootOntoPathObstacle(Vec3d root, Vec3d surface, Vec3d normal) {
		Vec3d path = surface.subtract(root);
		double pathLength = path.length();
		if (pathLength < 0.30D) {
			return surface;
		}
		Vec3d pathDirection = path.multiply(1.0D / pathLength);
		Vec3d start = root.add(pathDirection.multiply(Math.min(0.18D, pathLength * 0.12D)));
		Vec3d end = surface.add(normal.multiply(0.06D));
		BlockHitResult obstruction = getWorld().raycast(new RaycastContext(start, end,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		if (obstruction.getType() == HitResult.Type.MISS
				|| obstruction.getPos().squaredDistanceTo(surface) < 0.0625D) {
			return surface;
		}

		Vec3d obstacleSample = obstruction.getPos()
				.add(pathDirection.multiply(0.10D))
				.add(normal.multiply(0.04D));
		double outwardSearch = Math.max(1.25D, getRingDiameter() * 0.44D);
		double inwardSearch = Math.max(1.10D, getRingDiameter() * 0.38D);
		Vec3d topStart = obstacleSample.add(normal.multiply(outwardSearch));
		Vec3d topEnd = obstacleSample.subtract(normal.multiply(inwardSearch));
		BlockHitResult topHit = getWorld().raycast(new RaycastContext(topStart, topEnd,
				RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
		if (topHit.getType() == HitResult.Type.MISS
				|| Vec3d.of(topHit.getSide().getVector()).dotProduct(normal) <= 0.55D) {
			return null;
		}
		return topHit.getPos().add(normal.multiply(0.025D));
	}

	/** Searches below a leading leg when the expected support plane ends at a cliff. */
	private SpiderSurfaceTarget findSpiderDescendingLegSurface(Vec3d bodyTarget, Vec3d radial, Vec3d normal) {
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		if (safeNormal.y < 0.80D) {
			return null;
		}
		double maximumReach = spiderLegReach(getRingLevel());
		double rootRadius = getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO;
		Vec3d currentRoot = spiderBodyCenter().add(radial.multiply(rootRadius));
		double probeDepth = Math.max(4.5D, maximumReach * SPIDER_CLIFF_PROBE_DEPTH_RATIO);
		// Probe nearest columns first so the foot catches the cliff lip before a distant cave floor.
		for (double scale : new double[]{0.05D, 0.09D, 0.14D, 0.20D, 0.28D, 0.38D, 0.52D}) {
			double radialDistance = maximumReach * scale;
			Vec3d column = bodyTarget.add(radial.multiply(radialDistance));
			Vec3d start = column.subtract(safeNormal.multiply(Math.max(0.35D, spiderStandHeight() - 1.0D)));
			Vec3d end = column.subtract(safeNormal.multiply(spiderStandHeight() + probeDepth));
			BlockHitResult hit = getWorld().raycast(new RaycastContext(start, end,
					RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
			if (hit.getType() != HitResult.Type.MISS
					&& Vec3d.of(hit.getSide().getVector()).dotProduct(safeNormal) > 0.55D
					&& currentRoot.distanceTo(hit.getPos()) <= maximumReach * SPIDER_CLIFF_REACH_RATIO) {
				return new SpiderSurfaceTarget(hit.getPos(), radialDistance);
			}
		}
		return null;
	}

	private Vec3d spiderPlaneRadial(Vec3d normal, Vec3d travelForward, int index) {
		Vec3d safeForward = projectOntoPlane(travelForward, normal);
		if (safeForward.lengthSquared() < 0.01D) {
			safeForward = spiderTravelForward(normal, getBodyHeading());
		}
		safeForward = safeForward.normalize();
		Vec3d side = normal.crossProduct(safeForward).normalize();
		double angle = Math.PI * 2.0D * index / SPIDER_LEG_COUNT + Math.PI / 8.0D;
		return side.multiply(Math.cos(angle)).subtract(safeForward.multiply(Math.sin(angle))).normalize();
	}

	private Vec3d spiderLegRadial(Vec3d heading, int index) {
		Vec3d side = Vec3d.of(Direction.UP.getVector()).crossProduct(heading).normalize();
		double angle = Math.PI * 2.0D * index / SPIDER_LEG_COUNT + Math.PI / 8.0D;
		return side.multiply(Math.cos(angle)).subtract(heading.multiply(Math.sin(angle))).normalize();
	}

	private Vec3d spiderBodyCenter() {
		return getPos().add(0.0D, getRenderCenterHeight(), 0.0D);
	}

	private double spiderStandHeight() {
		return getRenderCenterHeight();
	}

	private void publishSpiderFoot(int index, Vec3d foot, Vec3d bodyCenter) {
		if (index < 0 || index >= SPIDER_LEG_COUNT || foot == null) {
			return;
		}
		Vec3d offset = foot.subtract(bodyCenter);
		dataTracker.set(SPIDER_FOOT_OFFSETS[index],
				new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
	}

	private void publishAllSpiderFeet() {
		if (!spiderFeetInitialized) {
			return;
		}
		Vec3d bodyCenter = spiderBodyCenter();
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			SpiderLegState leg = spiderLegs[index];
			if (leg != null) {
				publishSpiderFoot(index, leg.foot, bodyCenter);
			}
		}
	}

	private void spawnDiscTakeoffTurbulence(ServerWorld world, double liftStrength) {
		double strength = MathHelper.clamp(liftStrength, 0.0D, 1.0D);
		double radius = getCollisionOuterRadius();
		Vec3d origin = new Vec3d(getX(), getY() + 0.06D, getZ());
		int ringBursts = 18 + getRingLevel() * 8 + getDiscExtraMinecarts();
		for (int index = 0; index < ringBursts; index++) {
			double angle = Math.PI * 2.0D * index / ringBursts + random.nextDouble() * 0.12D;
			Vec3d radial = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle));
			Vec3d position = origin.add(radial.multiply(radius * (0.42D + random.nextDouble() * 0.48D)));
			Vec3d velocity = radial.multiply(0.11D + strength * (0.14D + random.nextDouble() * 0.12D))
					.add(0.0D, -0.08D - random.nextDouble() * (0.10D + strength * 0.10D), 0.0D);
			world.spawnParticles(index % 3 == 0 ? ParticleTypes.GUST : ParticleTypes.CLOUD,
					position.x, position.y, position.z, 0, velocity.x, velocity.y, velocity.z, 1.0D);
		}
		world.spawnParticles(ParticleTypes.POOF, origin.x, origin.y, origin.z,
				14 + getRingLevel() * 8, radius * 0.56D, 0.08D, radius * 0.56D, 0.12D + strength * 0.08D);
		world.spawnParticles(ParticleTypes.CLOUD, origin.x, origin.y - 0.04D, origin.z,
				22 + getRingLevel() * 10, radius * 0.42D, 0.05D, radius * 0.42D, 0.09D + strength * 0.07D);
	}

	/** Maintains a light downward and outward rotor wash while the flight disc is hovering. */
	private void spawnDiscHoverJets(ServerWorld world, double rotorSupport) {
		double strength = MathHelper.clamp(
				(rotorSupport - DISC_STALL_THRESHOLD) / (1.0D - DISC_STALL_THRESHOLD), 0.0D, 1.0D);
		double radius = getCollisionOuterRadius();
		int jetCount = 5 + getRingLevel() * 2 + getDiscExtraMinecarts() / 2;
		for (int index = 0; index < jetCount; index++) {
			double angle = Math.PI * 2.0D * index / jetCount + random.nextDouble() * 0.30D;
			Vec3d radial = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle));
			Vec3d position = new Vec3d(getX(), getY() + 0.03D, getZ())
					.add(radial.multiply(radius * (0.34D + random.nextDouble() * 0.52D)));
			Vec3d velocity = radial.multiply(0.035D + strength * 0.055D)
					.add(0.0D, -0.10D - strength * 0.10D - random.nextDouble() * 0.05D, 0.0D);
			world.spawnParticles(ParticleTypes.CLOUD, position.x, position.y, position.z,
					0, velocity.x, velocity.y, velocity.z, 1.0D);
		}
		if (age % 8 == 0) {
			world.spawnParticles(ParticleTypes.GUST, getX(), getY() + 0.04D, getZ(),
					0, 0.0D, -0.13D - strength * 0.09D, 0.0D, 1.0D);
		}
	}

	/** Emits the one-shot, wide exhaust burst produced when stored flight momentum is released. */
	private void spawnMomentumReleaseTurbulence(ServerWorld world, double charge) {
		double strength = MathHelper.clamp(charge, 0.0D, 1.0D);
		double radius = getCollisionOuterRadius();
		Vec3d origin = new Vec3d(getX(), getY() + 0.05D, getZ());
		int burstCount = 40 + getRingLevel() * 14 + getDiscExtraMinecarts() * 3;
		for (int index = 0; index < burstCount; index++) {
			double angle = Math.PI * 2.0D * index / burstCount + random.nextDouble() * 0.18D;
			Vec3d radial = new Vec3d(Math.cos(angle), 0.0D, Math.sin(angle));
			Vec3d position = origin.add(radial.multiply(radius * (0.45D + random.nextDouble() * 0.90D)))
					.add(0.0D, (random.nextDouble() - 0.5D) * 0.28D, 0.0D);
			Vec3d velocity = radial.multiply(0.18D + strength * (0.22D + random.nextDouble() * 0.18D))
					.add(0.0D, -0.16D - strength * (0.18D + random.nextDouble() * 0.20D), 0.0D);
			world.spawnParticles(index % 3 == 0 ? ParticleTypes.GUST : ParticleTypes.CLOUD,
					position.x, position.y, position.z, 0, velocity.x, velocity.y, velocity.z, 1.0D);
		}
		world.spawnParticles(ParticleTypes.POOF, origin.x, origin.y, origin.z,
				28 + (int) Math.round(strength * 28.0D), radius * (0.72D + strength * 0.28D),
				0.10D, radius * (0.72D + strength * 0.28D), 0.16D + strength * 0.12D);
		world.spawnParticles(ParticleTypes.CLOUD, origin.x, origin.y - 0.06D, origin.z,
				34 + (int) Math.round(strength * 32.0D), radius * (0.62D + strength * 0.25D),
				0.08D, radius * (0.62D + strength * 0.25D), 0.12D + strength * 0.10D);
	}

	/** Keeps the stored-momentum exhaust visible while its accelerated climb is active. */
	private void spawnMomentumReleaseJets(ServerWorld world, double charge) {
		double strength = MathHelper.clamp(charge, 0.0D, 1.0D);
		double radius = getCollisionOuterRadius();
		int jetCount = 4 + getRingLevel() * 2 + getDiscExtraMinecarts() / 2;
		for (int index = 0; index < jetCount; index++) {
			double angle = Math.PI * 2.0D * index / jetCount + random.nextDouble() * 0.25D;
			double x = getX() + Math.cos(angle) * radius * (0.55D + random.nextDouble() * 0.28D);
			double z = getZ() + Math.sin(angle) * radius * (0.55D + random.nextDouble() * 0.28D);
			world.spawnParticles(ParticleTypes.GUST, x, getY() + 0.05D, z, 0,
					0.0D, -0.18D - strength * 0.22D, 0.0D, 1.0D);
		}
	}

	private void playDiscLandingEffects(boolean fluidLanding, double impactStrength) {
		float strength = (float) MathHelper.clamp(impactStrength, 0.0D, 1.0D);
		if (fluidLanding) {
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_GENERIC_SPLASH,
					SoundCategory.NEUTRAL, 0.9F + strength * 0.7F, 0.72F + strength * 0.12F);
			if (getWorld() instanceof ServerWorld world) {
				world.spawnParticles(isInLava() ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
						getX(), fluidSurfaceY + 0.03D, getZ(), 12 + Math.round(strength * 20.0F),
						getRingDiameter() * 0.32D, 0.08D, getRingDiameter() * 0.32D, 0.12D);
			}
			return;
		}
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_LAND,
				SoundCategory.NEUTRAL, 0.85F + strength * 0.55F, 0.68F + strength * 0.16F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_ANVIL_LAND,
				SoundCategory.NEUTRAL, 0.25F + strength * 0.55F, 0.62F);
		if (getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.04D, getZ(),
					6 + Math.round(strength * 14.0F), getRingDiameter() * 0.22D,
					0.05D, getRingDiameter() * 0.22D, 0.035D);
		}
	}

	/** Accepts mining only while both a recent control packet and server-side mode allow it. */
	private void tickManualMining(PlayerEntity controller) {
		if (controller instanceof ServerPlayerEntity serverPlayer
				&& isMiningModeEnabled()
				&& controlMiningHeld
				&& age - lastMiningControlAge <= 3) {
			mineAtCrosshair(serverPlayer, controlMiningDirection);
		} else {
			miningProgress.clear();
		}
	}

	/**
	 * Advances clutch spin, home-position alignment, and final torque transfer.
	 * Returning {@code true} disconnects terrain-mode drive while the clutch is not idle.
	 */
	private boolean tickClutch(PlayerEntity controller, boolean hasFreshControl, double throttle) {
		if (controller == null || !hasReinforcedClutch()) {
			resetClutchState();
			return false;
		}
		boolean requested = hasFreshControl && controlClutchHeld;
		int phase = getClutchPhase();
		if (phase == CLUTCH_IDLE && requested) {
			if (isDiscMode()) {
				discClutchRotorStartSpeed = getFlightRotorSpeed();
			}
			setClutchPhase(CLUTCH_SPINNING);
			phase = CLUTCH_SPINNING;
		}
		if (phase == CLUTCH_SPINNING) {
			if (!requested) {
				beginClutchAlignment();
			} else {
				tickClutchSpin(throttle);
			}
		} else if (phase == CLUTCH_ALIGNING) {
			if (requested) {
				if (isDiscMode()) {
					discClutchRotorStartSpeed = getFlightRotorSpeed();
				}
				setClutchPhase(CLUTCH_SPINNING);
			} else {
				tickClutchAlignment();
			}
		} else if (phase == CLUTCH_ENGAGED) {
			clutchEngagementDelay--;
			if (clutchEngagementDelay <= 0) {
				performClutchLaunch(controller);
			}
		}
		return getClutchPhase() != CLUTCH_IDLE;
	}

	private void tickClutchSpin(double throttle) {
		float angularSpeed = getInnerCartAngularSpeed();
		if (Math.abs(throttle) > MIN_INPUT) {
			float direction = (float) (isDiscMode() ? Math.signum(throttle) : -Math.signum(throttle));
			float target = (float) (direction * maximumClutchAngularSpeed());
			angularSpeed = (float) approach(angularSpeed, target, clutchAngularAcceleration());
		} else {
			angularSpeed *= CLUTCH_IDLE_ANGULAR_DRAG;
			if (Math.abs(angularSpeed) < 0.05F) {
				angularSpeed = 0.0F;
			}
		}
		setInnerCartAngularSpeed(angularSpeed);
		setInnerCartAngle(normalizeDegrees(getTrackedInnerCartAngle() + angularSpeed));
	}

	private void beginClutchAlignment() {
		float angle = getTrackedInnerCartAngle();
		float angularSpeed = getInnerCartAngularSpeed();
		storedLaunchAngularSpeed = angularSpeed;
		if (Math.abs(angularSpeed) < 0.75F) {
			storedLaunchAngularSpeed = 0.0F;
			float signedHomeDistance = MathHelper.wrapDegrees(-angle);
			if (Math.abs(signedHomeDistance) < 1.0F) {
				resetClutchState();
				return;
			}
			angularSpeed = Math.copySign(MIN_ALIGNMENT_SPEED_DEGREES, signedHomeDistance);
		} else {
			angularSpeed = Math.copySign(Math.max(Math.abs(angularSpeed), MIN_ALIGNMENT_SPEED_DEGREES), angularSpeed);
		}
		setInnerCartAngularSpeed(angularSpeed);
		setClutchPhase(CLUTCH_ALIGNING);
	}

	private void tickClutchAlignment() {
		float previous = getTrackedInnerCartAngle();
		float angularSpeed = getInnerCartAngularSpeed();
		float next = normalizeDegrees(previous + angularSpeed);
		setInnerCartAngle(next);
		boolean crossedHome = angularSpeed > 0.0F ? next < previous : next > previous;
		if (!crossedHome) {
			return;
		}
		setInnerCartAngle(0.0F);
		setInnerCartAngularSpeed(0.0F);
		setClutchPhase(CLUTCH_ENGAGED);
		clutchEngagementDelay = 1;
		float strength = clutchMomentumStrength();
		dataTracker.set(LAUNCH_STRENGTH, strength);
		if (strength > 0.01F) {
			float weight = (float) Math.sqrt(strength);
			getWorld().playSound(null, getX(), getY() + 0.8D, getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
					SoundCategory.NEUTRAL, 0.12F + 1.72F * weight, 0.48F + 0.20F * strength);
			getWorld().playSound(null, getX(), getY() + 0.8D, getZ(), SoundEvents.BLOCK_ANVIL_LAND,
					SoundCategory.NEUTRAL, 0.08F + 1.12F * weight, 0.45F + 0.16F * strength);
		}
		if (strength > 0.01F && getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.3D, getZ(),
					3 + Math.round(18.0F * strength), 0.45D + 0.42D * strength,
					0.10D + 0.12D * strength, 0.45D + 0.42D * strength, 0.025D + 0.05D * strength);
		}
	}

	private void performClutchLaunch(PlayerEntity controller) {
		if (isDiscMode()) {
			performDiscRotorTransfer(controller);
			return;
		}
		Vec3d launchAxis = getForwardVector();
		if (launchAxis.lengthSquared() < 0.01D) {
			launchAxis = getBodyHeading();
		}
		launchAxis = launchAxis.normalize();
		Vec3d currentVelocity = getVelocity();
		double currentAlong = currentVelocity.dotProduct(launchAxis);
		double relativeTangentialSpeed = -Math.toRadians(storedLaunchAngularSpeed)
				* getInnerCartOrbitRadius() * CLUTCH_TRANSFER_EFFICIENCY;
		double maximumLaunchSpeed = maximumClutchLaunchSpeed();
		double launchedAlong = MathHelper.clamp(currentAlong + relativeTangentialSpeed,
				-maximumLaunchSpeed, maximumLaunchSpeed);
		double appliedImpulse = launchedAlong - currentAlong;
		Vec3d preservedOtherAxes = currentVelocity.subtract(launchAxis.multiply(currentAlong));
		setVelocity(preservedOtherAxes.add(launchAxis.multiply(launchedAlong)));
		velocityDirty = true;
		float strength = clutchMomentumStrength();
		dataTracker.set(LAUNCH_STRENGTH, strength);
		launchMomentumTicks = strength > 0.01F ? 20 + Math.round(strength * 30.0F) : 0;
		if (strength > 0.01F && Math.abs(appliedImpulse) > 1.0E-4D) {
			double transferredSpeedRatio = MathHelper.clamp(
					Math.abs(appliedImpulse) / Math.max(0.01D, maximumLaunchSpeed), 0.0D, 1.0D);
			ItemStack clutch = inventory.getStack(RingVehicleInventory.CLUTCH_SLOT);
			int maximumWear = Math.max(1, clutch.getMaxDamage() / 3);
			int wear = Math.max(1, (int) Math.ceil(maximumWear * transferredSpeedRatio));
			damageClutch(wear, controller);
		}
		storedLaunchAngularSpeed = 0.0F;
		setClutchPhase(CLUTCH_IDLE);
		setInnerCartAngle(0.0F);
		setInnerCartAngularSpeed(0.0F);
		if (strength > 0.01F) {
			if (getWorld() instanceof ServerWorld world) {
				double directionSign = Math.abs(appliedImpulse) > 1.0E-4D
						? Math.signum(appliedImpulse)
						: Math.signum(relativeTangentialSpeed);
				spawnClutchLaunchTurbulence(world, launchAxis.multiply(directionSign), strength);
			}
			dataTracker.set(LAUNCH_EVENT, dataTracker.get(LAUNCH_EVENT) + 1);
		}
	}

	/** Transfers signed inner-cart RPM without promoting weak RPM to stable hover power. */
	private void performDiscRotorTransfer(PlayerEntity controller) {
		float strength = clutchMomentumStrength();
		float direction = Math.signum(storedLaunchAngularSpeed);
		if (strength > 0.01F && direction != 0.0F) {
			float transferEfficiency = 0.92F + strength * 0.08F;
			setFlightRotorSpeed(storedLaunchAngularSpeed * transferEfficiency);
			ItemStack clutch = inventory.getStack(RingVehicleInventory.CLUTCH_SLOT);
			int maximumWear = Math.max(1, clutch.getMaxDamage() / 3);
			damageClutch(Math.max(1, (int) Math.ceil(maximumWear * strength)), controller);
		}
		dataTracker.set(LAUNCH_STRENGTH, strength);
		storedLaunchAngularSpeed = 0.0F;
		setClutchPhase(CLUTCH_IDLE);
		setInnerCartAngle(0.0F);
		setInnerCartAngularSpeed(0.0F);
	}

	private void spawnClutchLaunchTurbulence(ServerWorld world, Vec3d launchDirection, float strength) {
		if (launchDirection.lengthSquared() < 0.01D) {
			return;
		}
		Vec3d direction = launchDirection.normalize();
		Vec3d worldUp = Vec3d.of(Direction.UP.getVector());
		Vec3d side = worldUp.crossProduct(direction);
		if (side.lengthSquared() < 0.01D) {
			side = worldUp.crossProduct(getBodyHeading());
		}
		if (side.lengthSquared() < 0.01D) {
			side = new Vec3d(1.0D, 0.0D, 0.0D);
		}
		side = side.normalize();
		double radius = getCollisionOuterRadius();
		Vec3d origin = center().subtract(direction.multiply(radius * 0.72D));
		int airCount = 18 + Math.round(strength * 28.0F) + getRingLevel() * 10;
		for (int index = 0; index < airCount; index++) {
			double sideOffset = (random.nextDouble() - 0.5D) * (0.75D + radius * 0.42D);
			double verticalOffset = (random.nextDouble() - 0.5D) * radius * 1.45D;
			double backwardOffset = random.nextDouble() * radius * 0.40D;
			Vec3d position = origin
					.add(side.multiply(sideOffset))
					.add(worldUp.multiply(verticalOffset))
					.subtract(direction.multiply(backwardOffset));
			Vec3d velocity = direction.negate().multiply(0.08D + strength * (0.14D + random.nextDouble() * 0.16D))
					.add(side.multiply((random.nextDouble() - 0.5D) * (0.10D + strength * 0.18D)))
					.add(worldUp.multiply((random.nextDouble() - 0.35D) * (0.06D + strength * 0.12D)));
			world.spawnParticles(index % 5 == 0 ? ParticleTypes.GUST : ParticleTypes.CLOUD,
					position.x, position.y, position.z, 0, velocity.x, velocity.y, velocity.z, 1.0D);
		}

		for (int index = 0; index < 8 + Math.round(strength * 10.0F); index++) {
			double sideSign = index % 2 == 0 ? -1.0D : 1.0D;
			Vec3d position = center()
					.add(side.multiply(sideSign * (COLLISION_HALF_THICKNESS + 0.10D)))
					.add(worldUp.multiply((random.nextDouble() - 0.5D) * radius * 1.2D));
			Vec3d curl = direction.negate().multiply(0.05D + strength * 0.12D)
					.add(side.multiply(sideSign * (0.08D + strength * 0.10D)))
					.add(worldUp.multiply((random.nextDouble() - 0.5D) * 0.10D));
			world.spawnParticles(ParticleTypes.POOF, position.x, position.y, position.z,
					0, curl.x, curl.y, curl.z, 1.0D);
		}

		Vec3d contactNormal = getContactNormal().lengthSquared() < 0.5D ? worldUp : getContactNormal().normalize();
		Vec3d contactPoint = center().subtract(contactNormal.multiply(radius + 0.10D));
		BlockPos surfacePos = BlockPos.ofFloored(contactPoint);
		BlockState surface = world.getBlockState(surfacePos);
		if (!surface.isAir() && !surface.getCollisionShape(world, surfacePos).isEmpty()) {
			world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, surface),
					contactPoint.x, contactPoint.y, contactPoint.z,
					10 + Math.round(strength * 22.0F),
					0.42D + radius * 0.16D, 0.10D, 0.42D + radius * 0.16D,
					0.08D + strength * 0.16D);
		} else if (isFloatingVehicle()) {
			world.spawnParticles(isInLava() ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
					contactPoint.x, contactPoint.y, contactPoint.z,
					8 + Math.round(strength * 20.0F),
					0.55D + radius * 0.12D, 0.12D, 0.55D + radius * 0.12D,
					0.12D + strength * 0.22D);
		}
	}

	private void resetClutchState() {
		if (getClutchPhase() == CLUTCH_IDLE && Math.abs(getTrackedInnerCartAngle()) < 0.01F
				&& Math.abs(getInnerCartAngularSpeed()) < 0.01F) {
			return;
		}
		storedLaunchAngularSpeed = 0.0F;
		clutchEngagementDelay = 0;
		setClutchPhase(CLUTCH_IDLE);
		setInnerCartAngle(0.0F);
		setInnerCartAngularSpeed(0.0F);
	}

	/** Accumulates only after the same inner-cart threshold that previously triggered lift. */
	private void chargeMomentumStorage(PlayerEntity controller, double angularRatio) {
		if (momentumStorageCharge >= 1.0F) {
			return;
		}
		double thresholdProgress = MathHelper.clamp(
				(angularRatio - DISC_LIFT_THRESHOLD) / (1.0D - DISC_LIFT_THRESHOLD), 0.0D, 1.0D);
		float previous = momentumStorageCharge;
		momentumStorageCharge = (float) Math.min(1.0D,
				momentumStorageCharge + MOMENTUM_STORAGE_MIN_RATE
						+ (MOMENTUM_STORAGE_MAX_RATE - MOMENTUM_STORAGE_MIN_RATE) * thresholdProgress);
		if (previous < 1.0F && momentumStorageCharge >= 1.0F) {
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_WHIRL,
					SoundCategory.NEUTRAL, 0.65F, 1.45F);
			controller.sendMessage(Text.translatable("message.echominecart.momentum_storage_full"), true);
		}
	}

	private double maximumClutchAngularSpeed() {
		double ringScale = isExpandedRing() ? 1.15D : 1.0D;
		if (!getVariant().powered()) {
			return 72.0D * ringScale;
		}
		return (isHighGear() ? 156.0D : 96.0D) * ringScale;
	}

	private double clutchAngularAcceleration() {
		double inertiaScale = isExpandedRing() ? 0.82D : 1.0D;
		if (!getVariant().powered()) {
			return 0.70D * inertiaScale;
		}
		return (isHighGear() ? 1.25D : 0.95D) * inertiaScale;
	}

	private double maximumClutchLaunchSpeed() {
		double ringScale = isExpandedRing() ? 1.55D : 1.0D;
		if (!getVariant().powered()) {
			return 6.0D * ringScale;
		}
		return (isHighGear() ? 10.0D : 8.0D) * ringScale;
	}

	private float clutchMomentumStrength() {
		return MathHelper.clamp(Math.abs(storedLaunchAngularSpeed) / (float) maximumClutchAngularSpeed(), 0.0F, 1.0F);
	}

	private static float normalizeDegrees(float angle) {
		float normalized = angle % 360.0F;
		return normalized < 0.0F ? normalized + 360.0F : normalized;
	}

	/** Re-bases travel onto a new support plane while preserving direction and useful momentum. */
	private Vec3d beginSurfaceTransition(Vec3d oldNormal, Vec3d newNormal, Vec3d travel,
			double input, boolean inward, double speed) {
		Vec3d newTravel = inward ? oldNormal : oldNormal.negate();
		newTravel = projectOntoPlane(newTravel, newNormal);
		if (newTravel.lengthSquared() < 0.01D) {
			newTravel = projectOntoPlane(travel, newNormal);
		}
		newTravel = newTravel.normalize();
		Vec3d newForward = input < 0.0D ? newTravel.negate() : newTravel;
		setContactNormal(newNormal);
		setForwardVector(newForward);
		if (dashTicks > 0) {
			dashDirection = newForward;
		}
		setVelocity(newTravel.multiply(Math.max(inward ? 0.13D : 0.17D, speed)));
		transitionTicks = inward ? 8 : 14;
		contactSwitchCooldown = CONTACT_SWITCH_COOLDOWN;
		contactMissingTicks = 0;
		clearPendingContact();
		wallHeadingLockTicks = Math.abs(newNormal.y) < 0.5D && Math.abs(newForward.y) > 0.55D ? 14 : 0;
		return newForward;
	}

	private boolean updateFluidContact() {
		double surface = findFluidSurface();
		if (Double.isFinite(surface)) {
			fluidSurfaceY = surface;
			fluidGraceTicks = 7;
		} else if (fluidGraceTicks > 0) {
			fluidGraceTicks--;
		} else {
			fluidSurfaceY = Double.NaN;
		}
		return fluidGraceTicks > 0;
	}

	private double findFluidSurface() {
		double bestSurface = Double.NEGATIVE_INFINITY;
		BlockPos surfaceSeed = null;
		int baseY = MathHelper.floor(getY());
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				for (int y = baseY - 1; y <= baseY + 1; y++) {
					BlockPos pos = BlockPos.ofFloored(getX() + x, y, getZ() + z);
					var fluid = getWorld().getFluidState(pos);
					boolean supportedFluid = fluid.isIn(FluidTags.WATER)
							|| (isLavaProof() && fluid.isIn(FluidTags.LAVA));
					if (supportedFluid) {
						double candidateSurface = pos.getY() + fluid.getHeight(getWorld(), pos);
						if (candidateSurface > bestSurface) {
							bestSurface = candidateSurface;
							surfaceSeed = pos;
						}
					}
				}
			}
		}
		if (surfaceSeed == null) {
			return Double.NaN;
		}
		BlockPos cursor = surfaceSeed;
		for (int i = 0; i < 96; i++) {
			BlockPos above = cursor.up();
			var fluid = getWorld().getFluidState(above);
			boolean supportedFluid = fluid.isIn(FluidTags.WATER)
					|| (isLavaProof() && fluid.isIn(FluidTags.LAVA));
			if (!supportedFluid) {
				break;
			}
			cursor = above;
			bestSurface = cursor.getY() + fluid.getHeight(getWorld(), cursor);
		}
		return bestSurface;
	}

	private double buoyancyVelocity(double currentVerticalVelocity) {
		if (!Double.isFinite(fluidSurfaceY)) {
			return currentVerticalVelocity * 0.55D;
		}
		double targetY = fluidSurfaceY - 0.14D;
		double heightError = MathHelper.clamp(targetY - getY(), -0.55D, 0.55D);
		return MathHelper.clamp(currentVerticalVelocity * 0.42D + heightError * 0.16D, -0.085D, 0.085D);
	}

	private boolean raisedGroundAhead(Vec3d travel) {
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d probe = center().add(travel.multiply(1.05D)).add(up);
		return surfaceScore(probe, up) >= HIGH_WALL_SUPPORT_SAMPLES;
	}

	private boolean lowRiseAhead(Vec3d travel) {
		Vec3d horizontal = new Vec3d(travel.x, 0.0D, travel.z);
		if (horizontal.lengthSquared() < 0.5D) {
			return false;
		}
		horizontal = horizontal.normalize();
		double front = getCollisionForwardRadius();
		for (double distance : new double[]{Math.max(0.75D, front - 0.40D), front + 0.12D}) {
			BlockPos obstacle = BlockPos.ofFloored(
					getX() + horizontal.x * distance,
					getY() + 0.35D,
					getZ() + horizontal.z * distance);
			BlockState lower = getWorld().getBlockState(obstacle);
			if (lower.isAir() || lower.getCollisionShape(getWorld(), obstacle).isEmpty()) {
				continue;
			}
			BlockPos above = obstacle.up();
			BlockState upper = getWorld().getBlockState(above);
			if (upper.isAir() || upper.getCollisionShape(getWorld(), above).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private int wallSupportAhead(Vec3d travel) {
		Direction travelAxis = dominantDirection(travel);
		Vec3d wallNormal = Vec3d.of(travelAxis.getOpposite().getVector());
		return surfaceScore(center().add(travel.multiply(0.18D)), wallNormal);
	}

	private int leadingEdgeSupport(Vec3d travel, Vec3d normal) {
		return surfaceScore(center().add(travel.multiply(1.05D)), normal);
	}

	private boolean attemptGroundEscape(Vec3d travel) {
		if (travel.lengthSquared() < 0.5D || Math.abs(getContactNormal().y) < 0.9D) {
			return false;
		}
		Vec3d horizontal = new Vec3d(travel.x, 0.0D, travel.z);
		if (horizontal.lengthSquared() < 0.01D) {
			return false;
		}
		horizontal = horizontal.normalize();
		for (double lift : new double[]{0.20D, 0.38D, 0.62D, 0.95D}) {
			Vec3d offset = horizontal.multiply(0.16D).add(0.0D, lift, 0.0D);
			Box candidate = getBoundingBox().offset(offset).contract(0.025D);
			if (getWorld().isSpaceEmpty(this, candidate)) {
				move(MovementType.SELF, offset);
				setVelocity(horizontal.multiply(Math.max(0.10D, getVelocity().horizontalLength()))
						.add(0.0D, 0.08D, 0.0D));
				return true;
			}
		}
		return false;
	}

	private boolean attemptExpandedCornerDeflection(Vec3d travel) {
		if (travel.lengthSquared() < 0.01D) {
			return false;
		}
		Vec3d normal = getContactNormal().lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: getContactNormal().normalize();
		Vec3d tangent = travel.normalize();
		Vec3d lateral = normal.crossProduct(tangent);
		if (lateral.lengthSquared() < 0.01D) {
			lateral = Vec3d.of(Direction.UP.getVector()).crossProduct(getBodyHeading());
		}
		if (lateral.lengthSquared() < 0.01D) {
			return false;
		}
		lateral = lateral.normalize();
		for (double distance : new double[]{0.07D, 0.13D, 0.20D}) {
			for (double sign : new double[]{-1.0D, 1.0D}) {
				Vec3d offset = lateral.multiply(sign * distance)
						.add(normal.multiply(0.035D))
						.add(tangent.multiply(0.035D));
				if (getWorld().isSpaceEmpty(this, getBoundingBox().offset(offset).contract(0.035D))) {
					move(MovementType.SELF, offset);
					return true;
				}
			}
		}
		return false;
	}

	public void queueJump(float charge) {
		if (inventory.getStack(RingVehicleInventory.ABILITY_JUMP).isOf(Items.RABBIT_FOOT)) {
			if (isDiscMode() && (!isDiscFlightActive() || discSmashActive)) {
				return;
			}
			pendingJumpCharge = MathHelper.clamp(charge, 0.0F, 1.0F);
			jumpQueued = true;
		}
	}

	public void queueDash(float charge) {
		ItemStack sugar = inventory.getStack(RingVehicleInventory.ABILITY_DASH);
		if (!sugar.isOf(Items.SUGAR) || dashCooldown > 0
				|| isDiscMode() && (!isDiscFlightActive() || discSmashActive)) {
			return;
		}
		double moduleStrength = MathHelper.clamp(sugar.getCount() / 64.0D, 1.0D / 64.0D, 1.0D);
		double chargeStrength = MathHelper.clamp(charge, 0.0F, 1.0F);
		double releaseStrength = 0.20D + chargeStrength * 0.80D;
		dashTicks = 4 + MathHelper.ceil(moduleStrength * releaseStrength * 9.0D);
		dashForce = (0.07D + moduleStrength * 0.24D) * releaseStrength;
		dashSpeedLimit = (0.85D + moduleStrength * 3.15D) * (0.42D + releaseStrength * 0.58D);
		Vec3d surfaceForward = getForwardVector();
		Vec3d contactNormal = getContactNormal();
		boolean crestLaunch = !isDiscMode()
				&& Math.abs(contactNormal.y) < 0.5D
				&& surfaceForward.y > 0.55D
				&& (contactMissingTicks > 0
						|| surfaceScore(center().add(surfaceForward.multiply(1.25D)), contactNormal) < TRANSITION_SUPPORT_SAMPLES);
		dashDirection = isDiscMode() || crestLaunch ? getBodyHeading() : surfaceForward;
		if (dashDirection.lengthSquared() < 0.01D) {
			dashDirection = getBodyHeading();
		}
		dashDirection = dashDirection.normalize();
		if (isSpiderMode() && (jumpQueued
				|| abilityAirborne && spiderJumpDashWindowTicks > 0)) {
			activateSpiderWallCapture();
		}
		if (crestLaunch) {
			abilityAirborne = true;
			transitionTicks = 0;
			wallHeadingLockTicks = 0;
			contactMissingTicks = CONTACT_GRACE_TICKS + 1;
			setContactNormal(Vec3d.of(Direction.UP.getVector()));
			setForwardVector(dashDirection);
			dataTracker.set(FLOATING, false);
		}
		dashCooldown = DASH_COOLDOWN_TICKS;
		contactSwitchCooldown = Math.min(contactSwitchCooldown, 2);
		getWorld().playSound(null, getX(), getY() + 0.8D, getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),
				SoundCategory.NEUTRAL, 0.82F + (float) releaseStrength * 0.62F,
				0.68F + (float) moduleStrength * 0.17F + (float) chargeStrength * 0.16F);
		getWorld().playSound(null, getX(), getY() + 0.8D, getZ(), SoundEvents.ITEM_MACE_SMASH_AIR,
				SoundCategory.NEUTRAL, 0.22F + (float) releaseStrength * 0.58F,
				0.52F + (float) chargeStrength * 0.22F);
	}

	public void queueSmash(float charge) {
		ItemStack module = inventory.getStack(RingVehicleInventory.ABILITY_SMASH);
		if (!module.isOf(Items.HEAVY_CORE) && !module.isOf(Items.MACE)) {
			return;
		}
		float strength = MathHelper.clamp(charge, 0.0F, 1.0F);
		if (isDiscMode()) {
			if (!isDiscFlightActive()) {
				return;
			}
			activeSmashCharge = strength;
			discSmashFullCharge = strength >= DISC_SMASH_FULL_CHARGE;
			discSmashTicks = 8 + MathHelper.ceil(strength * 18.0F);
			double moduleMultiplier = module.isOf(Items.MACE) ? 1.08D : 1.0D;
			discSmashSpeed = MathHelper.lerp(strength, DISC_SMASH_MIN_SPEED, DISC_SMASH_MAX_SPEED)
					* moduleMultiplier;
			discSmashActive = true;
			discEmergencyLiftTicks = 0;
			discEmergencyLiftSpeed = 0.0D;
			jumpQueued = false;
			dashTicks = 0;
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_AIR,
					SoundCategory.NEUTRAL, 0.72F + strength * 0.65F, 0.72F - strength * 0.18F);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),
					SoundCategory.NEUTRAL, 0.30F + strength * 0.68F, 0.72F - strength * 0.16F);
			return;
		}
		if (abilityAirborne || (!isOnGround() && getVelocity().y < -0.08D)) {
			abilityAirborne = true;
			activeSmashCharge = strength;
			smashQueued = true;
		}
	}

	private void beginDiscEmergencyLift() {
		jumpQueued = false;
		ItemStack feet = inventory.getStack(RingVehicleInventory.ABILITY_JUMP);
		if (!isDiscFlightActive() || discSmashActive || !feet.isOf(Items.RABBIT_FOOT)) {
			return;
		}
		double moduleStrength = Math.sqrt(MathHelper.clamp(feet.getCount() / 64.0D, 1.0D / 64.0D, 1.0D));
		double chargeStrength = 0.35D + pendingJumpCharge * 0.65D;
		discEmergencyLiftTicks = 8 + MathHelper.ceil(pendingJumpCharge * 8.0F);
		discEmergencyLiftSpeed = (DISC_EMERGENCY_LIFT_BASE_SPEED
				+ DISC_EMERGENCY_LIFT_BONUS_SPEED * moduleStrength) * chargeStrength;
		SoundEvent jumpSound = random.nextBoolean()
				? SoundEvents.BLOCK_PISTON_EXTEND
				: SoundEvents.BLOCK_PISTON_CONTRACT;
		getWorld().playSound(null, getX(), getY(), getZ(), jumpSound,
				SoundCategory.NEUTRAL, 0.72F + pendingJumpCharge * 0.42F,
				0.68F + pendingJumpCharge * 0.18F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_JUMP,
				SoundCategory.NEUTRAL, 0.48F + pendingJumpCharge * 0.42F,
				0.82F + (float) moduleStrength * 0.18F);
		if (getWorld() instanceof ServerWorld world) {
			world.spawnParticles(ParticleTypes.CLOUD, getX(), getY() - 0.02D, getZ(),
					8, getRingDiameter() * 0.25D, 0.04D, getRingDiameter() * 0.25D, 0.04D);
		}
	}

	private void clearDiscAbilityState() {
		jumpQueued = false;
		discEmergencyLiftTicks = 0;
		discEmergencyLiftSpeed = 0.0D;
		discSmashActive = false;
		discSmashFullCharge = false;
		discSmashTicks = 0;
		discSmashSpeed = 0.0D;
		dashTicks = 0;
	}

	/** Applies network-queued abilities before ordinary terrain contact is evaluated. */
	private void processQueuedAbilities() {
		if (spiderJumpDashWindowTicks > 0) {
			spiderJumpDashWindowTicks--;
		}
		if (jumpQueued) {
			jumpQueued = false;
			ItemStack feet = inventory.getStack(RingVehicleInventory.ABILITY_JUMP);
			Vec3d supportNormal = getContactNormal().lengthSquared() < 0.5D
					? Vec3d.of(Direction.UP.getVector())
					: getContactNormal().normalize();
			boolean spiderSupported = isSpiderMode() && isSpiderAwake()
					&& (spiderMotionSupportCount() > 0 || isFloatingVehicle());
			if (!abilityAirborne && feet.isOf(Items.RABBIT_FOOT)
					&& (supportNormal.y > 0.8D || spiderSupported)) {
				double blocks = 1.0D + Math.max(0, feet.getCount() - 1);
				double charge = 0.15D + pendingJumpCharge * 0.85D;
				double jumpVelocity = Math.sqrt(0.16D * Math.max(0.25D, blocks * charge));
				Vec3d launchDirection = supportNormal;
				if (spiderSupported && Math.abs(supportNormal.y) < 0.65D) {
					launchDirection = supportNormal.multiply(0.82D)
							.add(Vec3d.of(Direction.UP.getVector()).multiply(0.45D))
							.normalize();
				}
				Vec3d retainedVelocity = spiderSupported
						? projectOntoPlane(getVelocity(), supportNormal).multiply(0.72D)
						: getVelocity().multiply(1.0D, 0.0D, 1.0D);
				setVelocity(retainedVelocity.add(
						launchDirection.multiply(Math.min(3.2D, jumpVelocity))));
				abilityAirborne = true;
				if (isSpiderMode()) {
					spiderJumpDashWindowTicks = SPIDER_JUMP_DASH_WINDOW_TICKS;
					if (dashTicks > 0) {
						activateSpiderWallCapture();
					}
				}
				fluidJumpGraceTicks = isFloatingVehicle() || Double.isFinite(findFluidSurface())
						? FLUID_JUMP_GRACE_TICKS : 0;
				landingBouncesRemaining = 0;
				landingBounceVelocity = 0.0D;
				dataTracker.set(FLOATING, false);
				setContactNormal(Vec3d.of(Direction.UP.getVector()));
				contactMissingTicks = CONTACT_GRACE_TICKS + 1;
				SoundEvent jumpSound = random.nextBoolean()
						? SoundEvents.BLOCK_PISTON_EXTEND
						: SoundEvents.BLOCK_PISTON_CONTRACT;
				getWorld().playSound(null, getX(), getY(), getZ(), jumpSound,
						SoundCategory.NEUTRAL, 0.88F + pendingJumpCharge * 0.42F,
						0.58F + pendingJumpCharge * 0.20F);
				getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),
						SoundCategory.NEUTRAL, 0.22F + pendingJumpCharge * 0.42F,
						0.62F + pendingJumpCharge * 0.18F);
				if (getWorld() instanceof ServerWorld world) {
					world.spawnParticles(ParticleTypes.CLOUD, getX(), getY() + 0.05D, getZ(),
							7, 0.55D, 0.04D, 0.55D, 0.025D);
				}
			}
		}
		if (smashQueued && abilityAirborne) {
			smashQueued = false;
			smashing = true;
			spiderWallCaptureTicks = 0;
			double strength = MathHelper.clamp(activeSmashCharge, 0.0F, 1.0F);
			ItemStack module = inventory.getStack(RingVehicleInventory.ABILITY_SMASH);
			double moduleMultiplier = module.isOf(Items.MACE) ? 1.10D : 1.0D;
			double initialSpeed = MathHelper.lerp(strength, 0.95D, 3.0D) * moduleMultiplier;
			Vec3d velocity = getVelocity();
			setVelocity(velocity.x * 0.45D, Math.min(-initialSpeed,
					velocity.y - MathHelper.lerp(strength, 0.42D, 1.10D)), velocity.z * 0.45D);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_AIR,
					SoundCategory.NEUTRAL, 0.72F + activeSmashCharge * 0.75F,
					0.74F - activeSmashCharge * 0.20F);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(),
					SoundCategory.NEUTRAL, 0.30F + activeSmashCharge * 0.62F,
					0.72F - activeSmashCharge * 0.18F);
		}
	}

	private Vec3d applyDashForce(Vec3d velocity) {
		if (dashTicks <= 0) {
			return velocity;
		}
		dashTicks--;
		Vec3d direction = dashDirection.lengthSquared() < 0.01D ? getForwardVector() : dashDirection;
		Vec3d accelerated = velocity.add(direction.multiply(dashForce));
		double alongDash = accelerated.dotProduct(direction);
		if (alongDash > dashSpeedLimit) {
			accelerated = accelerated.add(direction.multiply(dashSpeedLimit - alongDash));
		}
		if (getWorld() instanceof ServerWorld world && age % 2 == 0) {
			Vec3d trail = center().subtract(direction.multiply(getCollisionOuterRadius() + 0.32D));
			world.spawnParticles(ParticleTypes.CLOUD, trail.x, trail.y, trail.z,
					3, 0.22D, 0.22D, 0.22D, 0.025D);
		}
		return accelerated;
	}

	private Vec3d applyAirSteering(Vec3d velocity) {
		if (smashing || !(getFirstPassenger() instanceof PlayerEntity)
				|| lastControlAge < 0 || age - lastControlAge > 5
				|| Math.abs(controlSteering) <= MIN_INPUT) {
			return velocity;
		}
		double steering = MathHelper.clamp(controlSteering, (float) -MAX_STEERING_INPUT, (float) MAX_STEERING_INPUT);
		double turn = steering * AIR_TURN_RADIANS;
		Vec3d up = Vec3d.of(Direction.UP.getVector());
		Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
		if (horizontal.lengthSquared() > 1.0E-4D) {
			horizontal = rotateAroundAxis(horizontal, up, turn);
		} else {
			Vec3d nudge = rotateAroundAxis(getBodyHeading(), up, turn)
					.multiply(0.012D * Math.min(1.0D, Math.abs(steering)));
			horizontal = horizontal.add(nudge);
		}
		if (dashDirection.lengthSquared() > 0.01D) {
			dashDirection = rotateAroundAxis(dashDirection, up, turn).normalize();
		}
		if (horizontal.lengthSquared() > 1.0E-4D) {
			setForwardVector(horizontal.normalize());
		}
		setYaw(MathHelper.wrapDegrees(getYaw() - (float) Math.toDegrees(turn)));
		return new Vec3d(horizontal.x, velocity.y, horizontal.z);
	}

	private void tickAbilityAirborne() {
		if (fluidJumpGraceTicks > 0) {
			fluidJumpGraceTicks--;
		}
		double fluidSurface = findFluidSurface();
		if (Double.isFinite(fluidSurface)
				&& (fluidJumpGraceTicks <= 0 || getVelocity().y <= 0.0D)) {
			if (smashing) {
				finishFluidSmash(fluidSurface);
			}
			abilityAirborne = false;
			spiderWallCaptureTicks = 0;
			spiderJumpDashWindowTicks = 0;
			smashing = false;
			smashQueued = false;
			landingBouncesRemaining = 0;
			landingBounceVelocity = 0.0D;
			fluidSurfaceY = fluidSurface;
			fluidGraceTicks = 7;
			dataTracker.set(FLOATING, true);
			setContactNormal(Vec3d.of(Direction.UP.getVector()));
			setForwardVector(getBodyHeading());
			double targetY = fluidSurface - 0.14D;
			if (getY() < targetY) {
				double rise = targetY - getY();
				Box targetBounds = getBoundingBox().offset(0.0D, rise, 0.0D).contract(0.025D);
				if (getWorld().isSpaceEmpty(this, targetBounds)) {
					setPosition(getX(), targetY, getZ());
					updateCollisionBounds();
				}
			}
			double lift = buoyancyVelocity(getVelocity().y);
			Vec3d current = getVelocity();
			setVelocity(current.x * 0.82D, Math.max(lift, 0.045D), current.z * 0.82D);
			contactMissingTicks = 0;
			return;
		}
		SpiderWallTarget wallTarget = findSpiderWallCaptureTarget(getBodyHeading());
		Vec3d velocity = applyDashForce(applyAirSteering(getVelocity()));
		double smashStrength = MathHelper.clamp(activeSmashCharge, 0.0F, 1.0F);
		double gravity = smashing ? MathHelper.lerp(smashStrength, 0.14D, 0.30D)
				: landingBounceVelocity > 0.0D ? 0.045D : 0.08D;
		double maximumFallSpeed = smashing ? MathHelper.lerp(smashStrength, 2.4D, 3.8D) : 3.5D;
		velocity = new Vec3d(velocity.x * 0.995D,
				Math.max(-maximumFallSpeed, velocity.y - gravity), velocity.z * 0.995D);
		setVelocity(velocity);
		velocityDirty = true;
		Vec3d before = getPos();
		move(MovementType.SELF, velocity);
		damageEntitiesOnImpact(before);
		updateCollisionBounds();
		setPitch(0.0F);
		if (trySpiderWallCapture(wallTarget, getBodyHeading())) {
			for (Entity passenger : getPassengerList()) {
				passenger.fallDistance = 0.0F;
			}
			return;
		}
		boolean landed = groundCollision || (verticalCollision && velocity.y < 0.0D)
				|| getPos().squaredDistanceTo(before) < 1.0E-5D && velocity.y < -0.08D;
		if (landed) {
			spiderWallCaptureTicks = 0;
			spiderJumpDashWindowTicks = 0;
			double impactSpeed = Math.max(0.0D, -velocity.y);
			boolean wasSmashing = smashing;
			boolean bounced = !wasSmashing && continueLandingBounce(impactSpeed, velocity);
			abilityAirborne = bounced;
			if (!bounced) {
				setVelocity(getVelocity().multiply(0.88D, 0.0D, 0.88D));
			}
			setContactNormal(Vec3d.of(Direction.UP.getVector()));
			setForwardVector(getBodyHeading());
			contactMissingTicks = 0;
			contactSwitchCooldown = CONTACT_SWITCH_COOLDOWN;
			if (wasSmashing) {
				finishSmash();
			}
			smashing = false;
		}
		for (Entity passenger : getPassengerList()) {
			passenger.fallDistance = 0.0F;
		}
	}

	private void startLandingBounce(double impactSpeed, Vec3d impactVelocity) {
		landingBounceVelocity = MathHelper.clamp(impactSpeed * 0.16D, 0.14D, 0.30D);
		landingBouncesRemaining = 2;
		abilityAirborne = true;
		setVelocity(impactVelocity.x * 0.88D, landingBounceVelocity, impactVelocity.z * 0.88D);
		setContactNormal(Vec3d.of(Direction.UP.getVector()));
		setForwardVector(getBodyHeading());
		contactMissingTicks = CONTACT_GRACE_TICKS + 1;
	}

	private boolean continueLandingBounce(double impactSpeed, Vec3d impactVelocity) {
		if (landingBouncesRemaining <= 0) {
			if (impactSpeed < HARD_LANDING_SPEED) {
				landingBounceVelocity = 0.0D;
				return false;
			}
			startLandingBounce(impactSpeed, impactVelocity);
			return true;
		}
		landingBounceVelocity *= 0.44D;
		landingBouncesRemaining--;
		if (landingBounceVelocity < 0.025D) {
			landingBouncesRemaining = 0;
			landingBounceVelocity = 0.0D;
			return false;
		}
		setVelocity(impactVelocity.x * 0.9D, landingBounceVelocity, impactVelocity.z * 0.9D);
		return true;
	}

	private void finishSmash() {
		float strength = MathHelper.clamp(activeSmashCharge, 0.0F, 1.0F);
		float impactScale = 0.45F + strength * 0.85F;
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
				SoundCategory.NEUTRAL, 0.85F + impactScale * 0.75F, 0.72F - strength * 0.18F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_ANVIL_LAND,
				SoundCategory.NEUTRAL, 0.48F + impactScale * 0.58F, 0.70F - strength * 0.14F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE,
				SoundCategory.NEUTRAL, 0.22F + impactScale * 0.46F, 0.88F - strength * 0.20F);
		if (!(getWorld() instanceof ServerWorld world)) {
			activeSmashCharge = 0.0F;
			return;
		}
		world.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.15D, getZ(),
				20 + Math.round(strength * 44.0F), 0.85D + strength * 0.75D,
				0.12D + strength * 0.12D, 0.85D + strength * 0.75D, 0.07D + strength * 0.10D);
		spawnSmashBlockParticles(world, strength);
		double horizontalRange = 1.55D + strength * 1.35D;
		Box area = getBoundingBox().expand(horizontalRange, 0.55D + strength * 0.55D, horizontalRange);
		DamageSource damageSource = getFirstPassenger() instanceof PlayerEntity player
				? getDamageSources().playerAttack(player)
				: getDamageSources().generic();
		float damage = (float) ((6.0D + 34.0D * strength * strength)
				* (inventory.getStack(RingVehicleInventory.ABILITY_SMASH).isOf(Items.MACE) ? 1.20D : 1.0D));
		for (Entity entity : world.getOtherEntities(this, area, entity -> entity.isAlive() && !hasPassenger(entity))) {
			Vec3d away = entity.getPos().subtract(getPos()).multiply(1.0D, 0.0D, 1.0D);
			if (entity instanceof LivingEntity && !entity.isSpectator()) {
				entity.damage(damageSource, damage);
			}
			if (away.lengthSquared() > 0.01D) {
				Vec3d direction = away.normalize();
				double knockback = 0.32D + strength * 0.78D;
				entity.addVelocity(direction.x * knockback, 0.16D + strength * 0.42D,
						direction.z * knockback);
			}
		}
		activeSmashCharge = 0.0F;
	}

	private void spawnSmashBlockParticles(ServerWorld world, float strength) {
		int radius = strength < 0.55F ? 1 : 2;
		for (int offsetX = -2; offsetX <= 2; offsetX++) {
			for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
				if (Math.abs(offsetX) > radius || Math.abs(offsetZ) > radius) {
					continue;
				}
				BlockPos sample = BlockPos.ofFloored(
						getX() + offsetX * 0.62D, getY() - 0.08D, getZ() + offsetZ * 0.62D);
				BlockState state = world.getBlockState(sample);
				for (int depth = 0; depth < 3 && (state.isAir() || !world.getFluidState(sample).isEmpty()); depth++) {
					sample = sample.down();
					state = world.getBlockState(sample);
				}
				if (state.isAir() || state.getCollisionShape(world, sample).isEmpty()) {
					continue;
				}
				world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
						sample.getX() + 0.5D, sample.getY() + 1.02D, sample.getZ() + 0.5D,
						3 + Math.round(strength * 7.0F), 0.26D + strength * 0.12D,
						0.10D + strength * 0.10D, 0.26D + strength * 0.12D, 0.07D + strength * 0.10D);
			}
		}
	}

	private void finishFluidSmash(double surfaceY) {
		float strength = MathHelper.clamp(activeSmashCharge, 0.0F, 1.0F);
		BlockPos fluidPos = BlockPos.ofFloored(getX(), surfaceY - 0.12D, getZ());
		boolean lava = getWorld().getFluidState(fluidPos).isIn(FluidTags.LAVA);
		getWorld().playSound(null, getX(), surfaceY, getZ(),
				lava ? SoundEvents.BLOCK_LAVA_EXTINGUISH : SoundEvents.ENTITY_GENERIC_SPLASH,
				SoundCategory.NEUTRAL, (lava ? 0.82F : 0.92F) + strength * 0.88F,
				(lava ? 0.72F : 0.76F) - strength * 0.16F);
		getWorld().playSound(null, getX(), surfaceY, getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
				SoundCategory.NEUTRAL, 0.42F + strength * 0.78F, 0.68F - strength * 0.18F);
		if (!(getWorld() instanceof ServerWorld world)) {
			activeSmashCharge = 0.0F;
			return;
		}
		if (lava) {
			world.spawnParticles(ParticleTypes.LAVA, getX(), surfaceY + 0.10D, getZ(),
					20 + Math.round(strength * 42.0F), 0.85D + strength * 0.65D,
					0.12D + strength * 0.10D, 0.85D + strength * 0.65D, 0.14D + strength * 0.16D);
			world.spawnParticles(ParticleTypes.FLAME, getX(), surfaceY + 0.16D, getZ(),
					14 + Math.round(strength * 30.0F), 0.72D + strength * 0.58D,
					0.14D + strength * 0.14D, 0.72D + strength * 0.58D, 0.06D + strength * 0.08D);
			world.spawnParticles(ParticleTypes.LARGE_SMOKE, getX(), surfaceY + 0.24D, getZ(),
					10 + Math.round(strength * 24.0F), 0.68D + strength * 0.52D,
					0.16D + strength * 0.16D, 0.68D + strength * 0.52D, 0.04D + strength * 0.045D);
		} else {
			world.spawnParticles(ParticleTypes.SPLASH, getX(), surfaceY + 0.08D, getZ(),
					32 + Math.round(strength * 72.0F), 0.95D + strength * 0.75D,
					0.14D + strength * 0.10D, 0.95D + strength * 0.75D, 0.20D + strength * 0.28D);
			world.spawnParticles(ParticleTypes.BUBBLE, getX(), surfaceY - 0.10D, getZ(),
					18 + Math.round(strength * 42.0F), 0.78D + strength * 0.62D,
					0.12D + strength * 0.10D, 0.78D + strength * 0.62D, 0.10D + strength * 0.12D);
			world.spawnParticles(ParticleTypes.CLOUD, getX(), surfaceY + 0.14D, getZ(),
					8 + Math.round(strength * 20.0F), 0.70D + strength * 0.55D,
					0.08D + strength * 0.08D, 0.70D + strength * 0.55D, 0.05D + strength * 0.05D);
		}
		activeSmashCharge = 0.0F;
	}

	/** Applies speed-scaled module damage without consuming vehicle momentum. */
	private void damageEntitiesOnImpact(Vec3d positionBeforeMove) {
		int moduleType = getSmashModuleType();
		double speed = getVelocity().length();
		if (moduleType <= 0 || speed < MIN_IMPACT_ATTACK_SPEED || !(getWorld() instanceof ServerWorld world)) {
			return;
		}
		float multiplier = moduleType == 2 ? 12.0F : 10.0F;
		double linearDamage = Math.max(1.0D, (speed - 0.12D) * multiplier);
		double speedRatio = MathHelper.clamp(speed / maximumImpactDamageSpeed(), 0.0D, 1.0D);
		double exponentialDamage = MAX_IMPACT_DAMAGE * Math.pow(speedRatio, IMPACT_DAMAGE_EXPONENT);
		float damage = (float) MathHelper.clamp(
				Math.max(linearDamage, exponentialDamage), 1.0D, MAX_IMPACT_DAMAGE);
		Vec3d preservedVelocity = getVelocity();
		DamageSource damageSource = getFirstPassenger() instanceof PlayerEntity player
				? getDamageSources().playerAttack(player)
				: getDamageSources().generic();
		Vec3d movement = getPos().subtract(positionBeforeMove);
		Box impactArea = getBoundingBox().stretch(movement.negate()).expand(0.12D);
		for (Entity entity : world.getOtherEntities(this, impactArea,
				target -> target instanceof LivingEntity && target.isAlive() && !target.isSpectator() && !hasPassengerDeep(target))) {
			if (impactAttackCooldowns.getOrDefault(entity.getId(), 0) > age) {
				continue;
			}
			entity.damage(damageSource, damage);
			impactAttackCooldowns.put(entity.getId(), age + IMPACT_ATTACK_COOLDOWN_TICKS);
		}
		setVelocity(preservedVelocity);
	}

	private static double maximumImpactDamageSpeed() {
		double expandedHighGearAngularSpeed = Math.toRadians(144.0D * 1.2D);
		double expandedOrbitRadius = INNER_CART_ORBIT_RADIUS + MAX_RING_LEVEL;
		return expandedHighGearAngularSpeed * expandedOrbitRadius * CLUTCH_TRANSFER_EFFICIENCY;
	}

	public boolean hasMiningTool() {
		for (int slot = 0; slot < RingVehicleInventory.TOOL_SLOTS; slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty() && stack.get(DataComponentTypes.TOOL) != null) {
				return true;
			}
		}
		return false;
	}

	private void tickMovementEffects(boolean floating) {
		double speed = getVelocity().length();
		if (speed < 0.035D || !(getWorld() instanceof ServerWorld world)) {
			movementSoundTicker = 0;
			return;
		}
		movementSoundTicker++;
		float speedFactor = (float) MathHelper.clamp(speed / Math.max(0.1D, getVariant().maximumSpeed(isHighGear())), 0.0D, 1.0D);
		boolean lavaFloating = floating && isInLava();
		if (floating && movementSoundTicker % Math.max(3, 8 - Math.round(speedFactor * 4.0F)) == 0) {
			Vec3d side = getBodyHeading().crossProduct(Vec3d.of(Direction.UP.getVector())).normalize();
			for (double sign : new double[]{-1.0D, 1.0D}) {
				double splashY = Double.isFinite(fluidSurfaceY) ? fluidSurfaceY + 0.03D : getY() + 0.28D;
				Vec3d splash = new Vec3d(getX(), splashY, getZ()).add(side.multiply(sign * 0.48D));
				world.spawnParticles(lavaFloating ? ParticleTypes.LAVA : ParticleTypes.SPLASH,
						splash.x, splash.y, splash.z, lavaFloating ? 1 : 2 + Math.round(speedFactor * 3.0F),
						0.16D, 0.04D, 0.16D, lavaFloating ? 0.0D : 0.05D + speed * 0.08D);
			}
			world.playSound(null, getX(), getY() + 0.25D, getZ(),
					lavaFloating ? SoundEvents.BLOCK_LAVA_POP : SoundEvents.ENTITY_BOAT_PADDLE_WATER,
					SoundCategory.NEUTRAL, 0.12F + speedFactor * 0.16F, 0.82F + speedFactor * 0.34F);
		}
		if (isSpiderMode()) {
			return;
		}
		int soundInterval = Math.max(14, 26 - Math.round(speedFactor * 8.0F));
		if (movementSoundTicker % soundInterval != 0) {
			return;
		}
		SoundEvent movementSound;
		float basePitch;
		if (isLavaProof() && getVariant().powered()) {
			movementSound = SoundEvents.BLOCK_NETHERITE_BLOCK_STEP;
			basePitch = 0.98F;
		} else if (isLavaProof()) {
			movementSound = SoundEvents.BLOCK_BASALT_STEP;
			basePitch = 0.66F;
		} else if (getVariant().powered()) {
			movementSound = SoundEvents.BLOCK_AMETHYST_BLOCK_STEP;
			basePitch = 1.12F;
		} else {
			movementSound = SoundEvents.BLOCK_METAL_STEP;
			basePitch = 0.82F;
		}
		world.playSound(null, getX(), getY() + 1.0D, getZ(), movementSound, SoundCategory.NEUTRAL,
				0.06F + speedFactor * 0.12F, basePitch + speedFactor * 0.32F);
	}

	/** Counts collision samples beneath the ring; higher scores indicate a more stable contact patch. */
	private int surfaceScore(Vec3d center, Vec3d normal) {
		if (normal.lengthSquared() < 0.5D) {
			return 0;
		}
		Vec3d[] axes = contactPlaneAxes(normal);
		int score = 0;
		int sampleRadius = 1 + getRingLevel();
		for (int a = -sampleRadius; a <= sampleRadius; a++) {
			for (int b = -sampleRadius; b <= sampleRadius; b++) {
				Vec3d sample = center.subtract(normal.multiply(getContactRadius()))
						.add(axes[0].multiply(a * CONTACT_SAMPLE_SPACING))
						.add(axes[1].multiply(b * CONTACT_SAMPLE_SPACING));
				BlockPos pos = BlockPos.ofFloored(sample);
				BlockState state = getWorld().getBlockState(pos);
				if (!state.isAir() && !state.getCollisionShape(getWorld(), pos).isEmpty()) {
					score++;
				}
			}
		}
		return score;
	}

	/** Chooses a nearby normal while preferring contact continuity over one-tick geometric gains. */
	private Vec3d bestNearbyContact(Vec3d center, Vec3d preferred) {
		if (surfaceScore(center, preferred) >= MIN_SUPPORT_SAMPLES) {
			return preferred;
		}
		Vec3d best = Vec3d.ZERO;
		int bestScore = 0;
		for (Direction direction : Direction.values()) {
			Vec3d candidate = Vec3d.of(direction.getVector());
			int score = surfaceScore(center, candidate);
			if (score > bestScore) {
				best = candidate;
				bestScore = score;
			}
		}
		return bestScore >= 3 ? best : Vec3d.ZERO;
	}

	private boolean confirmPendingContact(Vec3d candidate) {
		if (candidate.lengthSquared() < 0.5D) {
			clearPendingContact();
			return false;
		}
		if (pendingContactNormal.lengthSquared() > 0.5D
				&& pendingContactNormal.dotProduct(candidate) > 0.99D) {
			pendingContactTicks++;
		} else {
			pendingContactNormal = candidate;
			pendingContactTicks = 1;
		}
		return pendingContactTicks >= CONTACT_SWITCH_TICKS;
	}

	private void clearPendingContact() {
		pendingContactNormal = Vec3d.ZERO;
		pendingContactTicks = 0;
	}

	private static Vec3d[] contactPlaneAxes(Vec3d normal) {
		if (Math.abs(normal.y) > 0.5D) {
			return new Vec3d[]{new Vec3d(1.0D, 0.0D, 0.0D), new Vec3d(0.0D, 0.0D, 1.0D)};
		}
		if (Math.abs(normal.x) > 0.5D) {
			return new Vec3d[]{new Vec3d(0.0D, 1.0D, 0.0D), new Vec3d(0.0D, 0.0D, 1.0D)};
		}
		return new Vec3d[]{new Vec3d(0.0D, 1.0D, 0.0D), new Vec3d(1.0D, 0.0D, 0.0D)};
	}

	/**
	 * Mines a plane centered on the server raycast target. Progress is keyed by block position so the
	 * beam can advance without sharing vanilla player-breaking state.
	 */
	private void mineAtCrosshair(ServerPlayerEntity player, Vec3d aimDirection) {
		if (!(getWorld() instanceof ServerWorld world) || aimDirection.lengthSquared() < 0.5D) {
			miningProgress.clear();
			return;
		}
		Vec3d direction = aimDirection.normalize();
		Vec3d start = player.getEyePos();
		double reach = miningReachFrom(start, direction);
		Set<Long> activeTargets = new HashSet<>();
		BlockPos previousHit = null;
		for (int layer = 0; layer < MAX_MINING_LAYERS_PER_TICK; layer++) {
			BlockHitResult hit = world.raycast(new RaycastContext(start, start.add(direction.multiply(reach)),
					RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
			if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(previousHit)) {
				break;
			}
			previousHit = hit.getBlockPos();
			Vec3d[] axes = contactPlaneAxes(Vec3d.of(hit.getSide().getVector()));
			Vec3d targetCenter = Vec3d.ofCenter(hit.getBlockPos());
			boolean brokeAny = false;
			boolean hasPendingBlock = false;
			for (int a = -1; a <= 1; a++) {
				for (int b = -1; b <= 1; b++) {
					BlockPos pos = BlockPos.ofFloored(targetCenter.add(axes[0].multiply(a)).add(axes[1].multiply(b)));
					if (!activeTargets.add(pos.asLong())) {
						continue;
					}
					ItemStack tool = bestMiningTool(world.getBlockState(pos));
					if (tool.isEmpty()) {
						continue;
					}
					MiningBlockResult result = progressMiningBlock(world, player, tool, pos);
					brokeAny |= result == MiningBlockResult.BROKEN;
					hasPendingBlock |= result == MiningBlockResult.PENDING;
				}
			}
			if (!brokeAny || hasPendingBlock) {
				break;
			}
		}
		if (previousHit == null) {
			miningProgress.clear();
			return;
		}
		miningProgress.keySet().retainAll(activeTargets);
	}

	private double miningReachFrom(Vec3d origin, Vec3d direction) {
		Vec3d offset = origin.subtract(center());
		double projection = offset.dotProduct(direction);
		double radius = getCollisionOuterRadius();
		double discriminant = projection * projection - (offset.lengthSquared() - radius * radius);
		double ringExit = discriminant >= 0.0D
				? -projection + Math.sqrt(discriminant)
				: radius;
		return Math.max(0.0D, ringExit) + MINING_REACH_BEYOND_RING
				+ getRingLevel() * EXPANDED_MINING_REACH_BONUS;
	}

	private ItemStack bestMiningTool(BlockState state) {
		ItemStack best = ItemStack.EMPTY;
		float bestScore = Float.NEGATIVE_INFINITY;
		for (int slot = 0; slot < RingVehicleInventory.TOOL_SLOTS; slot++) {
			ItemStack candidate = inventory.getStack(slot);
			if (candidate.isEmpty() || candidate.get(DataComponentTypes.TOOL) == null) {
				continue;
			}
			float score = candidate.getMiningSpeedMultiplier(state)
					+ (candidate.isSuitableFor(state) ? 10_000.0F : 0.0F);
			if (score > bestScore) {
				best = candidate;
				bestScore = score;
			}
		}
		return best;
	}

	private MiningBlockResult progressMiningBlock(ServerWorld world, ServerPlayerEntity player,
			ItemStack tool, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		float hardness = state.getHardness(world, pos);
		if (state.isAir() || hardness < 0.0F || !player.canModifyAt(world, pos)) {
			return MiningBlockResult.SKIPPED;
		}
		float speed = Math.max(0.1F, tool.getMiningSpeedMultiplier(state));
		float divisor = tool.isSuitableFor(state) ? 30.0F : 100.0F;
		float increment = speed / Math.max(0.05F, hardness) / divisor * MANUAL_MINING_SPEED_MULTIPLIER;
		long key = pos.asLong();
		float progress = miningProgress.getOrDefault(key, 0.0F) + increment;
		if (progress < 1.0F) {
			miningProgress.put(key, progress);
			return MiningBlockResult.PENDING;
		}
		BlockEntity blockEntity = world.getBlockEntity(pos);
		Block.dropStacks(state, world, pos, blockEntity, player, tool);
		boolean broken = world.breakBlock(pos, false, player);
		if (broken) {
			tool.postMine(world, state, pos, player);
			tool.damage(1, world, player, item -> {
			});
			inventory.markDirty();
		}
		miningProgress.remove(key);
		return broken ? MiningBlockResult.BROKEN : MiningBlockResult.SKIPPED;
	}

	private enum MiningBlockResult {
		SKIPPED,
		PENDING,
		BROKEN
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		ItemStack held = player.getStackInHand(hand);
		if (player.isSneaking() && held.getItem() instanceof SpiderLegItem legItem && isSpiderMode()) {
			return installSpiderLeg(player, held, legItem, null);
		}
		if (player.isSneaking() && held.isOf(Items.MINECART) && isDiscMode()) {
			if (!isExpandedRing()) {
				if (!getWorld().isClient()) {
					player.sendMessage(Text.translatable("message.echominecart.disc_minecart_expanded_only"), true);
				}
				return ActionResult.success(getWorld().isClient());
			}
			if (getDiscExtraMinecarts() >= MAX_DISC_EXTRA_MINECARTS) {
				if (!getWorld().isClient()) {
					player.sendMessage(Text.translatable("message.echominecart.disc_minecart_full"), true);
				}
				return ActionResult.success(getWorld().isClient());
			}
			if (!getWorld().isClient()) {
				setDiscExtraMinecarts(getDiscExtraMinecarts() + 1);
				if (!player.getAbilities().creativeMode) {
					held.decrement(1);
				}
				getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_MINECART_INSIDE,
						SoundCategory.NEUTRAL, 0.9F, 0.72F + getDiscExtraMinecarts() * 0.035F);
				player.sendMessage(Text.translatable("message.echominecart.disc_minecart_added",
						getDiscExtraMinecarts() + 1), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (player.isSneaking() && held.isOf(Items.CHEST) && !hasChestAttached()) {
			if (!getWorld().isClient()) {
				inventory.setStack(RingVehicleInventory.ABILITY_CHEST, new ItemStack(Items.CHEST));
				if (!player.getAbilities().creativeMode) {
					held.decrement(1);
				}
				player.sendMessage(Text.translatable("message.echominecart.ring_vehicle_chest_attached"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (player.isSneaking() && held.isEmpty() && getPassengerList().isEmpty()) {
			if (!getWorld().isClient()) {
				ItemStack stack = toItemStack();
				player.getInventory().offerOrDrop(stack);
				discard();
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (!player.isSneaking() && getPassengerList().isEmpty()) {
			if (isSpiderMode() && (!isSpiderAwake() || getSpiderDeployProgress() < 1.0F)) {
				if (!getWorld().isClient()) {
					player.sendMessage(Text.translatable("message.echominecart.spider_vehicle_sleeping"), true);
				}
				return ActionResult.success(getWorld().isClient());
			}
			if (!getWorld().isClient()) {
				player.startRiding(this);
			}
			return ActionResult.success(getWorld().isClient());
		}
		return ActionResult.PASS;
	}

	@Override
	public ActionResult interactAt(PlayerEntity player, Vec3d hitPos, Hand hand) {
		ItemStack held = player.getStackInHand(hand);
		if (player.isSneaking() && held.getItem() instanceof SpiderLegItem legItem && isSpiderMode()) {
			return installSpiderLeg(player, held, legItem, hitPos);
		}
		return interact(player, hand);
	}

	@Override
	public boolean canHit() {
		return !isRemoved() && getPassengerList().isEmpty();
	}

	@Override
	public float getTargetingMargin() {
		return 0.25F;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengerList().isEmpty()
				&& (!isSpiderMode() || isSpiderAwake() && getSpiderDeployProgress() >= 1.0F);
	}

	@Override
	public Vec3d getPassengerRidingPos(Entity passenger) {
		if (isSpiderMode()) {
			return center().add(trackedSpiderBodyNormal().multiply(SPIDER_SEAT_OFFSET));
		}
		float angle = getTrackedInnerCartAngle();
		double radians = Math.toRadians(angle);
		double orbitRadius = getInnerCartOrbitRadius();
		if (isDiscMode()) {
			Vec3d heading = getBodyHeading();
			Vec3d side = Vec3d.of(Direction.UP.getVector()).crossProduct(heading).normalize();
			return center()
					.add(side.multiply(orbitRadius * Math.cos(radians)))
					.add(heading.multiply(-orbitRadius * Math.sin(radians)))
					.add(0.0D, DISC_CART_VERTICAL_OFFSET, 0.0D);
		}
		double localY = -orbitRadius * Math.cos(radians);
		double localForward = -orbitRadius * Math.sin(radians);
		return center().add(0.0D, localY, 0.0D).add(getBodyHeading().multiply(localForward));
	}

	@Override
	public boolean isCollidable() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return true;
	}

	@Override
	public float getStepHeight() {
		return 1.15F;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		Entity attacker = source.getAttacker();
		if ((attacker != null && hasPassengerDeep(attacker))
				|| (source.getSource() != null && hasPassengerDeep(source.getSource()))) {
			return false;
		}
		if (getWorld().isClient() || isRemoved()) {
			return true;
		}
		if (attacker instanceof PlayerEntity player
				&& getPassengerList().isEmpty()
				&& player.getMainHandStack().isOf(Items.MACE)
				&& isSpiderMode()) {
			handleSpiderMaceStrike(player);
			return false;
		}
		if (attacker instanceof PlayerEntity player
				&& getPassengerList().isEmpty()
				&& player.getMainHandStack().isOf(Items.MACE)
				&& !isSpiderMode()) {
			toggleDiscMode(player);
			return false;
		}
		if (isLavaProof() && source.isIn(DamageTypeTags.IS_FIRE)) {
			extinguish();
			return false;
		}
		if (source.getAttacker() instanceof PlayerEntity player && player.getAbilities().creativeMode) {
			destroyAndDropContents(false);
			return true;
		}
		if (isSpiderMode()) {
			// Spider projectiles resolve legs and body themselves; vanilla explosion callbacks would double-hit.
			if (source.getSource() instanceof SpiderProjectileEntity
					|| source.getAttacker() instanceof SpiderProjectileEntity) {
				return false;
			}
			Vec3d impact = source.getSource() != null ? source.getSource().getPos()
					: source.getAttacker() != null ? source.getAttacker().getPos() : center();
			if (getSpiderInstalledLegCount() > 0) {
				damageSpiderLeg(nearestInstalledSpiderLeg(impact), amount, source, impact);
			} else {
				damageSpiderBody(amount, source, impact);
			}
			return true;
		}
		accumulatedDamage += amount;
		if (accumulatedDamage >= 8.0F) {
			destroyAndDropContents(true);
		}
		return true;
	}

	private ActionResult installSpiderLeg(PlayerEntity player, ItemStack held, SpiderLegItem legItem,
			Vec3d hitOffset) {
		if (isSpiderAwake()) {
			if (!getWorld().isClient()) {
				player.sendMessage(Text.translatable("message.echominecart.spider_leg_sleep_first"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (!legItem.matches(this)) {
			if (!getWorld().isClient()) {
				player.sendMessage(Text.translatable("message.echominecart.spider_leg_incompatible"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (getSpiderPendingLegIndex() >= 0) {
			if (!getWorld().isClient()) {
				player.sendMessage(Text.translatable("message.echominecart.spider_leg_hammer_required"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (getSpiderVisibleLegMask() == SPIDER_FULL_LEG_MASK) {
			if (!getWorld().isClient()) {
				player.sendMessage(Text.translatable("message.echominecart.spider_leg_full"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		int legIndex = spiderLegIndexAt(player, hitOffset);
		if ((getSpiderVisibleLegMask() & 1 << legIndex) != 0) {
			if (!getWorld().isClient()) {
				player.sendMessage(Text.translatable("message.echominecart.spider_leg_slot_occupied"), true);
			}
			return ActionResult.success(getWorld().isClient());
		}
		if (!getWorld().isClient()) {
			dataTracker.set(SPIDER_PENDING_LEG, legIndex);
			spiderFeetInitialized = false;
			if (!player.getAbilities().creativeMode) {
				held.decrement(1);
			}
			getWorld().playSound(null, getX(), getY() + 0.45D, getZ(), SoundEvents.BLOCK_CHAIN_PLACE,
					SoundCategory.NEUTRAL, 0.78F, 0.86F + legIndex * 0.018F);
			player.sendMessage(Text.translatable("message.echominecart.spider_leg_placed", legIndex + 1), true);
		}
		return ActionResult.success(getWorld().isClient());
	}

	private void handleSpiderMaceStrike(PlayerEntity player) {
		if (isSpiderSleepTransition()) {
			return;
		}
		if (getSpiderPendingLegIndex() >= 0) {
			fusePendingSpiderLeg(player);
		} else if (isSpiderAwake()) {
			sleepSpider(player);
		} else {
			wakeSpider(player);
		}
	}

	private void fusePendingSpiderLeg(PlayerEntity player) {
		int legIndex = getSpiderPendingLegIndex();
		if (legIndex < 0 || legIndex >= SPIDER_LEG_COUNT) {
			return;
		}
		dataTracker.set(SPIDER_LEG_MASK, getSpiderInstalledLegMask() | 1 << legIndex);
		spiderLegHealth[legIndex] = SPIDER_LEG_MAX_HEALTH;
		dataTracker.set(SPIDER_PENDING_LEG, -1);
		dataTracker.set(SPIDER_RETRACTING_LEG, legIndex);
		dataTracker.set(SPIDER_LEG_RETRACT_TICKS, SPIDER_LEG_RETRACT_DURATION_TICKS);
		spiderFeetInitialized = false;
		getWorld().playSound(null, getX(), getY() + 0.45D, getZ(), SoundEvents.BLOCK_ANVIL_USE,
				SoundCategory.NEUTRAL, 0.88F, 0.68F);
		getWorld().playSound(null, getX(), getY() + 0.45D, getZ(), SoundEvents.BLOCK_CHAIN_PLACE,
				SoundCategory.NEUTRAL, 0.62F, 1.18F);
		player.sendMessage(Text.translatable("message.echominecart.spider_leg_fused",
				getSpiderInstalledLegCount(), SPIDER_LEG_COUNT), true);
	}

	private void wakeSpider(PlayerEntity player) {
		if (getSpiderInstalledLegCount() <= 0) {
			player.sendMessage(Text.translatable("message.echominecart.spider_leg_none"), true);
			return;
		}
		dataTracker.set(SPIDER_AWAKE, true);
		dataTracker.set(SPIDER_SLEEP_TRANSITION, false);
		dataTracker.set(SPIDER_RETRACTING_LEG, -1);
		dataTracker.set(SPIDER_LEG_RETRACT_TICKS, 0);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS, 0.0F);
		spiderFeetInitialized = false;
		setVelocity(Vec3d.ZERO);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND,
				SoundCategory.NEUTRAL, 0.95F, 0.78F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN,
				SoundCategory.NEUTRAL, 0.72F, 0.64F);
		getWorld().playSound(null, getX(), getY() + 0.55D, getZ(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL,
				SoundCategory.NEUTRAL, 0.72F, 1.58F);
		getWorld().playSound(null, getX(), getY() + 0.35D, getZ(), SoundEvents.ENTITY_SPIDER_AMBIENT,
				SoundCategory.NEUTRAL, 0.52F, 0.68F);
		player.sendMessage(Text.translatable("message.echominecart.spider_vehicle_awakened"), true);
	}

	private void sleepSpider(PlayerEntity player) {
		dataTracker.set(SPIDER_AWAKE, true);
		dataTracker.set(SPIDER_SLEEP_TRANSITION, true);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS,
				MathHelper.clamp(Math.max(0.35F, getSpiderDeployProgress()), 0.0F, 1.0F));
		setVelocity(Vec3d.ZERO);
		getWorld().playSound(null, getX(), getY() + 0.35D, getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
				SoundCategory.NEUTRAL, 0.86F, 1.12F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_PISTON_CONTRACT,
				SoundCategory.NEUTRAL, 0.94F, 0.60F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
				SoundCategory.NEUTRAL, 0.82F, 0.72F);
		player.sendMessage(Text.translatable("message.echominecart.spider_vehicle_sleeping_enabled"), true);
	}

	private int spiderLegIndexAt(PlayerEntity player, Vec3d hitOffset) {
		Vec3d direction = hitOffset == null
				? Vec3d.ZERO
				: new Vec3d(hitOffset.x, 0.0D, hitOffset.z);
		if (direction.lengthSquared() < 0.04D) {
			Vec3d playerOffset = player.getPos().subtract(getPos());
			direction = new Vec3d(playerOffset.x, 0.0D, playerOffset.z);
		}
		if (direction.lengthSquared() < 0.01D) {
			direction = getBodyHeading();
		}
		direction = direction.normalize();
		int bestIndex = 0;
		double bestScore = -Double.MAX_VALUE;
		Vec3d heading = getBodyHeading();
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			double score = spiderLegRadial(heading, index).dotProduct(direction);
			if (score > bestScore) {
				bestScore = score;
				bestIndex = index;
			}
		}
		return bestIndex;
	}

	private void toggleDiscMode(PlayerEntity player) {
		boolean targetDiscMode = !isDiscMode();
		Box targetBounds = targetDiscMode
				? discCollisionBounds(getPos(), getRingLevel())
				: collisionBounds(getPos(), Vec3d.of(Direction.UP.getVector()), getBodyHeading(), getRingLevel());
		if (!getWorld().isSpaceEmpty(this, targetBounds.contract(0.025D))) {
			player.sendMessage(Text.translatable("message.echominecart.disc_mode_no_space"), true);
			return;
		}
		setDiscMode(targetDiscMode);
		setFlightRotorSpeed(0.0F);
		setDiscFlightActive(false);
		clearDiscAbilityState();
		discClutchRotorStartSpeed = 0.0F;
		setVelocity(Vec3d.ZERO);
		setContactNormal(Vec3d.of(Direction.UP.getVector()));
		setForwardVector(getBodyHeading());
		resetClutchState();
		updateCollisionBounds();
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
				SoundCategory.NEUTRAL, 1.15F, targetDiscMode ? 0.68F : 0.82F);
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_ANVIL_LAND,
				SoundCategory.NEUTRAL, 0.85F, targetDiscMode ? 0.56F : 0.70F);
		player.sendMessage(Text.translatable(targetDiscMode
				? "message.echominecart.disc_mode_enabled"
				: "message.echominecart.disc_mode_disabled"), true);
	}

	@Override
	public boolean isFireImmune() {
		return isLavaProof();
	}

	private void convertToItem() {
		dropAndDiscard(true);
	}

	private void dropAndDiscard(boolean drop) {
		removeAllPassengers();
		if (drop) {
			dropStack(toItemStack(), 0.25F);
		}
		discard();
	}

	private void destroyAndDropContents(boolean dropVehicle) {
		removeAllPassengers();
		List<ItemStack> contents = new ArrayList<>();
		for (int slot = 0; slot < inventory.stacks().size(); slot++) {
			ItemStack stack = inventory.stacks().get(slot);
			if (!stack.isEmpty()) {
				contents.add(stack.copy());
				inventory.stacks().set(slot, ItemStack.EMPTY);
			}
		}
		markInventoryDirty();
		if (dropVehicle) {
			dropStack(toItemStack(), 0.25F);
		}
		for (ItemStack stack : contents) {
			var dropped = dropStack(stack, 0.5F);
			if (dropped != null) {
				dropped.setVelocity((random.nextDouble() - 0.5D) * 0.34D,
						0.18D + random.nextDouble() * 0.16D,
						(random.nextDouble() - 0.5D) * 0.34D);
			}
		}
		discard();
	}

	public void openInventory(ServerPlayerEntity player) {
		player.openHandledScreen(new RingVehicleScreenHandler.Factory(this));
	}

	public void toggleGear(ServerPlayerEntity player) {
		if (!getVariant().powered()) {
			player.sendMessage(Text.translatable("message.echominecart.ring_vehicle_no_high_gear"), true);
			return;
		}
		if (!hasReinforcedClutch()) {
			player.sendMessage(Text.translatable("message.echominecart.ring_vehicle_clutch_required"), true);
			return;
		}
		setHighGear(!isHighGear());
		damageClutch(1, player);
		player.sendMessage(Text.translatable(isHighGear()
				? "message.echominecart.ring_vehicle_high_gear"
				: "message.echominecart.ring_vehicle_low_gear"), true);
	}

	public void toggleSpiderExplorationMode(ServerPlayerEntity player, int requestedMode) {
		if (!isSpiderMode() || !isSpiderAwake()) {
			return;
		}
		if (requestedMode != SPIDER_EXPLORATION_UP
				&& requestedMode != SPIDER_EXPLORATION_DOWN) {
			return;
		}
		int nextMode = getSpiderExplorationMode() == requestedMode
				? SPIDER_EXPLORATION_AUTO
				: requestedMode;
		dataTracker.set(SPIDER_EXPLORATION_MODE, nextMode);
		if (nextMode == SPIDER_EXPLORATION_AUTO) {
			spiderExplorationNormalTarget = Vec3d.ZERO;
		}
		String message = switch (nextMode) {
			case SPIDER_EXPLORATION_UP -> "message.echominecart.spider_exploration_up";
			case SPIDER_EXPLORATION_DOWN -> "message.echominecart.spider_exploration_down";
			default -> "message.echominecart.spider_exploration_auto";
		};
		player.sendMessage(Text.translatable(message), true);
	}

	private void damageClutch(int amount, PlayerEntity controller) {
		if (amount <= 0 || !(getWorld() instanceof ServerWorld world)) {
			return;
		}
		ItemStack clutch = inventory.getStack(RingVehicleInventory.CLUTCH_SLOT);
		if (!clutch.isOf(EchoMinecartRegistry.REINFORCED_CLUTCH)) {
			return;
		}
		ServerPlayerEntity serverPlayer = controller instanceof ServerPlayerEntity player ? player : null;
		clutch.damage(amount, world, serverPlayer, item -> {
		});
		inventory.markDirty();
	}

	public void toggleMiningMode(ServerPlayerEntity player) {
		if (!hasMiningTool()) {
			setMiningModeEnabled(false);
			player.sendMessage(Text.translatable("message.echominecart.ring_vehicle_mining_no_tool"), true);
			return;
		}
		setMiningModeEnabled(!isMiningModeEnabled());
		player.sendMessage(Text.translatable(isMiningModeEnabled()
				? "message.echominecart.ring_vehicle_mining_on"
				: "message.echominecart.ring_vehicle_mining_off"), true);
	}

	/** Toggles the aircraft-only momentum reservoir and transfers it on the falling edge. */
	public void toggleMomentumStorage(ServerPlayerEntity player) {
		if (!isDiscMode()) {
			player.sendMessage(Text.translatable("message.echominecart.momentum_storage_flight_only"), true);
			return;
		}
		if (!hasReinforcedClutch()) {
			player.sendMessage(Text.translatable("message.echominecart.ring_vehicle_clutch_required"), true);
			return;
		}
		if (!isMomentumStorageEnabled()) {
			setMomentumStorageEnabled(true);
			momentumReleaseActive = false;
			player.sendMessage(Text.translatable("message.echominecart.momentum_storage_on"), true);
			return;
		}
		setMomentumStorageEnabled(false);
		if (momentumStorageCharge > 0.0001F) {
			momentumReleaseActive = true;
			setDiscFlightActive(true);
			int releasedPercent = Math.round(momentumStorageCharge * 100.0F);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_WHIRL,
					SoundCategory.NEUTRAL, 1.15F + releasedPercent * 0.004F, 0.58F);
			getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_AIR,
					SoundCategory.NEUTRAL, 0.48F + releasedPercent * 0.003F, 0.72F);
			if (getWorld() instanceof ServerWorld world) {
				spawnMomentumReleaseTurbulence(world, momentumStorageCharge);
			}
			player.sendMessage(Text.translatable("message.echominecart.momentum_storage_release", releasedPercent), true);
		} else {
			momentumReleaseActive = false;
			player.sendMessage(Text.translatable("message.echominecart.momentum_storage_off"), true);
		}
	}

	/** Returns one optional flight-disc minecart module without affecting the primary cockpit. */
	public void removeDiscMinecart(ServerPlayerEntity player) {
		if (!isDiscMode() || getDiscExtraMinecarts() <= 0) {
			return;
		}
		setDiscExtraMinecarts(getDiscExtraMinecarts() - 1);
		player.getInventory().offerOrDrop(new ItemStack(Items.MINECART));
		getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_MINECART_INSIDE,
				SoundCategory.NEUTRAL, 0.75F, 1.08F);
		player.sendMessage(Text.translatable("message.echominecart.disc_minecart_removed",
				getDiscExtraMinecarts()), true);
	}

	public void acceptControlInput(float throttle, float steering, boolean clutchHeld) {
		if (!Float.isFinite(throttle) || !Float.isFinite(steering)) {
			return;
		}
		controlInput = MathHelper.clamp(throttle, -1.0F, 1.0F);
		controlSteering = MathHelper.clamp(steering, (float) -MAX_STEERING_INPUT, (float) MAX_STEERING_INPUT);
		controlClutchHeld = clutchHeld;
		lastControlAge = age;
	}

	public void acceptMiningInput(boolean active, Vec3d aimDirection) {
		if (!Double.isFinite(aimDirection.x) || !Double.isFinite(aimDirection.y)
				|| !Double.isFinite(aimDirection.z) || aimDirection.lengthSquared() < 0.25D) {
			controlMiningHeld = false;
			return;
		}
		controlMiningHeld = active;
		controlMiningDirection = aimDirection.normalize();
		lastMiningControlAge = age;
	}

	public RingVehicleInventory inventory() {
		return inventory;
	}

	public void markInventoryDirty() {
		setChestAttached(inventory.getStack(RingVehicleInventory.ABILITY_CHEST).isOf(Items.CHEST));
		boolean clutchInstalled = supportsReinforcedClutch()
				&& inventory.getStack(RingVehicleInventory.CLUTCH_SLOT).isOf(EchoMinecartRegistry.REINFORCED_CLUTCH);
		dataTracker.set(CLUTCH_INSTALLED, clutchInstalled);
		if (!clutchInstalled) {
			setHighGear(false);
			resetClutchState();
			setMomentumStorageEnabled(false);
			momentumReleaseActive = false;
		}
		ItemStack jump = inventory.getStack(RingVehicleInventory.ABILITY_JUMP);
		ItemStack dash = inventory.getStack(RingVehicleInventory.ABILITY_DASH);
		ItemStack smash = inventory.getStack(RingVehicleInventory.ABILITY_SMASH);
		dataTracker.set(JUMP_MODULE_STRENGTH, jump.isOf(Items.RABBIT_FOOT) ? jump.getCount() : 0);
		dataTracker.set(DASH_MODULE_STRENGTH, dash.isOf(Items.SUGAR) ? dash.getCount() : 0);
		dataTracker.set(SMASH_MODULE_TYPE, smash.isOf(Items.MACE) ? 2 : smash.isOf(Items.HEAVY_CORE) ? 1 : 0);
		if (!hasMiningTool()) {
			setMiningModeEnabled(false);
		}
	}

	/** Consumes one installed ammo item and fires only from a fully deployed spider body. */
	public void fireSpiderWeapon(ServerPlayerEntity player) {
		if (!(getWorld() instanceof ServerWorld world) || player.getVehicle() != this
				|| !isSpiderMode() || !isSpiderAwake() || getSpiderDeployProgress() < 0.95F
				|| spiderWeaponFireCooldown > 0) {
			return;
		}
		ItemStack ammunition = inventory.getStack(RingVehicleInventory.ABILITY_AMMO);
		SpiderAmmoType ammo = SpiderAmmoType.fromStack(ammunition);
		if (ammo == null) {
			player.sendMessage(Text.translatable("message.echominecart.spider_weapon_no_ammo"), true);
			return;
		}
		Vec3d direction = getSpiderWeaponForward();
		Vec3d muzzle = getSpiderWeaponMuzzle(direction);
		SpiderProjectileEntity projectile = SpiderProjectileEntity.create(world, player, ammo, muzzle, direction);
		if (!world.spawnEntity(projectile)) {
			return;
		}
		ammunition.decrement(1);
		markInventoryDirty();
		spiderWeaponFireCooldown = SPIDER_WEAPON_FIRE_COOLDOWN_TICKS + (ammo.explosive() ? 7 : 0);
		dataTracker.set(SPIDER_RECOIL_TICKS, SPIDER_WEAPON_RECOIL_TICKS);
		dataTracker.set(SPIDER_RECOIL_STRENGTH, (float) ammo.recoil());
		setVelocity(getVelocity().subtract(direction.multiply(ammo.recoil())));
		float screenImpact = MathHelper.clamp(0.45F + ammo.damage() / 42.0F * 0.45F
				+ (ammo.explosive() ? 0.10F : 0.0F), 0.45F, 1.0F);
		ServerPlayNetworking.send(player,
				new SpiderWeaponFiredPayload(screenImpact, ammo.explosive(), ammo.color()));
		if (ammo.explosive()) {
			world.playSound(null, muzzle.x, muzzle.y, muzzle.z,
					SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST,
					SoundCategory.PLAYERS, 1.15F, 0.72F);
		} else {
			float pressure = MathHelper.clamp((ammo.damage() - 6.0F) / 12.0F, 0.0F, 1.0F);
			world.playSound(null, muzzle.x, muzzle.y, muzzle.z,
					SoundEvents.ENTITY_WIND_CHARGE_THROW,
					SoundCategory.PLAYERS, 1.05F + pressure * 0.12F,
					0.78F - pressure * 0.08F + random.nextFloat() * 0.05F);
			world.playSound(null, muzzle.x, muzzle.y, muzzle.z,
					SoundEvents.ENTITY_WITHER_SHOOT,
					SoundCategory.PLAYERS, 0.68F + pressure * 0.16F,
					1.18F - pressure * 0.12F + random.nextFloat() * 0.05F);
			world.playSound(null, muzzle.x, muzzle.y, muzzle.z,
					SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
					SoundCategory.PLAYERS, 0.52F + pressure * 0.18F,
					1.32F - pressure * 0.16F + random.nextFloat() * 0.04F);
		}
		world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, muzzle.x, muzzle.y, muzzle.z,
				ammo.explosive() ? 18 : 8, 0.12D, 0.12D, 0.12D, 0.10D);
	}

	private Vec3d getSpiderWeaponForward() {
		Vec3d normal = trackedSpiderBodyNormal();
		Vec3d forward = projectOntoPlane(getForwardVector(), normal);
		if (forward.lengthSquared() < 0.01D) {
			forward = projectOntoPlane(getBodyHeading(), normal);
		}
		return forward.lengthSquared() < 0.01D ? getBodyHeading() : forward.normalize();
	}

	private Vec3d getSpiderWeaponMuzzle(Vec3d direction) {
		double radius = getRingDiameter() * 0.5D + 0.52D;
		return spiderBodyCenter().add(direction.multiply(radius))
				.add(trackedSpiderBodyNormal().multiply(0.16D));
	}

	/** Finds the first protected leg or body region crossed by one projectile tick. */
	public SpiderWeaponHit traceSpiderWeaponHit(Vec3d start, Vec3d end, double projectileRadius) {
		if (!isSpiderMode() || isRemoved()) {
			return null;
		}
		double bestFraction = Double.POSITIVE_INFINITY;
		int bestLeg = -1;
		Vec3d bestPosition = null;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			if (!hasFunctionalSpiderLeg(index)) {
				continue;
			}
			Vec3d root = spiderLegRootWorld(index);
			Vec3d foot = spiderLegFootWorld(index);
			SegmentDistance distance = closestSegmentDistance(start, end, root, foot);
			double hitRadius = projectileRadius + 0.38D;
			if (distance.distanceSquared() <= hitRadius * hitRadius
					&& distance.firstFraction() < bestFraction) {
				bestFraction = distance.firstFraction();
				bestLeg = index;
				bestPosition = start.lerp(end, bestFraction);
			}
		}

		var bodyHit = getBoundingBox().expand(projectileRadius).raycast(start, end);
		if (bodyHit.isPresent()) {
			double fraction = pathFraction(start, end, bodyHit.get());
			if (fraction < bestFraction) {
				bestFraction = fraction;
				bestPosition = bodyHit.get();
				bestLeg = getSpiderInstalledLegCount() > 0
						? nearestInstalledSpiderLeg(bestPosition)
						: -1;
			}
		}
		return bestPosition == null ? null : new SpiderWeaponHit(bestLeg, bestPosition, bestFraction);
	}

	public void applySpiderWeaponHit(int legIndex, float damage, DamageSource source, Vec3d impact) {
		if (getWorld().isClient() || !isSpiderMode() || damage <= 0.0F) {
			return;
		}
		if (getSpiderInstalledLegCount() > 0) {
			int protectedLeg = hasFunctionalSpiderLeg(legIndex)
					? legIndex
					: nearestInstalledSpiderLeg(impact);
			damageSpiderLeg(protectedLeg, damage, source, impact);
		} else {
			damageSpiderBody(damage, source, impact);
		}
	}

	public void applySpiderExplosionDamage(float damage, DamageSource source, Vec3d explosionCenter) {
		applySpiderWeaponHit(nearestInstalledSpiderLeg(explosionCenter), damage, source, explosionCenter);
	}

	private void damageSpiderLeg(int legIndex, float damage, DamageSource source, Vec3d impact) {
		if (!hasFunctionalSpiderLeg(legIndex)) {
			if (getSpiderInstalledLegCount() <= 0) {
				damageSpiderBody(damage, source, impact);
			}
			return;
		}
		spiderLegHealth[legIndex] = Math.max(0.0F, spiderLegHealth[legIndex] - damage);
		spawnSpiderArmorHit(impact, false);
		if (spiderLegHealth[legIndex] > 0.0F) {
			return;
		}
		dataTracker.set(SPIDER_LEG_MASK, getSpiderInstalledLegMask() & ~(1 << legIndex));
		spiderLegs[legIndex] = null;
		Vec3d breakPosition = spiderLegFootWorld(legIndex).lerp(spiderLegRootWorld(legIndex), 0.42D);
		if (getWorld() instanceof ServerWorld world) {
			world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK,
					Blocks.IRON_BLOCK.getDefaultState()), breakPosition.x, breakPosition.y, breakPosition.z,
					42, 0.55D, 0.55D, 0.55D, 0.20D);
			world.spawnParticles(ParticleTypes.EXPLOSION, breakPosition.x, breakPosition.y, breakPosition.z,
					2, 0.20D, 0.20D, 0.20D, 0.0D);
			world.playSound(null, breakPosition.x, breakPosition.y, breakPosition.z,
					SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.NEUTRAL, 0.95F, 1.18F);
			world.playSound(null, breakPosition.x, breakPosition.y, breakPosition.z,
					SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.NEUTRAL, 0.72F, 1.42F);
		}
	}

	private void damageSpiderBody(float damage, DamageSource source, Vec3d impact) {
		float riderDamage = damage * 0.15F;
		float absorbedDamage = damage - riderDamage;
		spiderBodyHealth = Math.max(0.0F, spiderBodyHealth - absorbedDamage);
		DamageSource riderSource = source;
		if (source.getSource() instanceof SpiderProjectileEntity projectile) {
			Entity owner = projectile.getOwnerEntity();
			riderSource = owner instanceof PlayerEntity player
					? getDamageSources().playerAttack(player)
					: getDamageSources().generic();
		}
		for (Entity passenger : List.copyOf(getPassengerList())) {
			if (passenger instanceof LivingEntity living) {
				living.damage(riderSource, riderDamage);
			}
		}
		spawnSpiderArmorHit(impact, true);
		if (spiderBodyHealth <= 0.0F) {
			destroyAndDropContents(true);
		}
	}

	private void spawnSpiderArmorHit(Vec3d impact, boolean bodyHit) {
		if (!(getWorld() instanceof ServerWorld world)) {
			return;
		}
		world.spawnParticles(bodyHit ? ParticleTypes.CRIT : ParticleTypes.ELECTRIC_SPARK,
				impact.x, impact.y, impact.z, bodyHit ? 14 : 9,
				0.20D, 0.20D, 0.20D, 0.08D);
		world.playSound(null, impact.x, impact.y, impact.z,
				bodyHit ? SoundEvents.BLOCK_ANVIL_HIT : SoundEvents.BLOCK_CHAIN_HIT,
				SoundCategory.NEUTRAL, bodyHit ? 0.82F : 0.58F, bodyHit ? 0.82F : 1.36F);
	}

	private int nearestInstalledSpiderLeg(Vec3d point) {
		int nearest = -1;
		double nearestDistance = Double.POSITIVE_INFINITY;
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			if (!hasFunctionalSpiderLeg(index)) {
				continue;
			}
			SegmentDistance distance = closestSegmentDistance(point, point,
					spiderLegRootWorld(index), spiderLegFootWorld(index));
			if (distance.distanceSquared() < nearestDistance) {
				nearestDistance = distance.distanceSquared();
				nearest = index;
			}
		}
		return nearest;
	}

	private Vec3d spiderLegRootWorld(int index) {
		Vec3d normal = trackedSpiderBodyNormal();
		Vec3d radial = spiderPlaneRadial(normal, getSpiderWeaponForward(), index);
		return spiderBodyCenter().add(radial.multiply(
				getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO));
	}

	private Vec3d spiderLegFootWorld(int index) {
		SpiderLegState leg = index >= 0 && index < SPIDER_LEG_COUNT ? spiderLegs[index] : null;
		if (leg != null && leg.foot.lengthSquared() > 0.01D) {
			return leg.foot;
		}
		Vector3f offset = dataTracker.get(SPIDER_FOOT_OFFSETS[MathHelper.clamp(index, 0, SPIDER_LEG_COUNT - 1)]);
		return spiderBodyCenter().add(offset.x(), offset.y(), offset.z());
	}

	private static SegmentDistance closestSegmentDistance(Vec3d firstStart, Vec3d firstEnd,
			Vec3d secondStart, Vec3d secondEnd) {
		Vec3d firstDirection = firstEnd.subtract(firstStart);
		Vec3d secondDirection = secondEnd.subtract(secondStart);
		Vec3d separation = firstStart.subtract(secondStart);
		double firstLength = firstDirection.dotProduct(firstDirection);
		double secondLength = secondDirection.dotProduct(secondDirection);
		double secondProjection = secondDirection.dotProduct(separation);
		double firstFraction;
		double secondFraction;
		if (firstLength <= 1.0E-8D && secondLength <= 1.0E-8D) {
			firstFraction = 0.0D;
			secondFraction = 0.0D;
		} else if (firstLength <= 1.0E-8D) {
			firstFraction = 0.0D;
			secondFraction = MathHelper.clamp(secondProjection / secondLength, 0.0D, 1.0D);
		} else {
			double firstProjection = firstDirection.dotProduct(separation);
			if (secondLength <= 1.0E-8D) {
				secondFraction = 0.0D;
				firstFraction = MathHelper.clamp(-firstProjection / firstLength, 0.0D, 1.0D);
			} else {
				double cross = firstDirection.dotProduct(secondDirection);
				double denominator = firstLength * secondLength - cross * cross;
				firstFraction = denominator == 0.0D ? 0.0D
						: MathHelper.clamp((cross * secondProjection - firstProjection * secondLength)
								/ denominator, 0.0D, 1.0D);
				secondFraction = (cross * firstFraction + secondProjection) / secondLength;
				if (secondFraction < 0.0D) {
					secondFraction = 0.0D;
					firstFraction = MathHelper.clamp(-firstProjection / firstLength, 0.0D, 1.0D);
				} else if (secondFraction > 1.0D) {
					secondFraction = 1.0D;
					firstFraction = MathHelper.clamp((cross - firstProjection) / firstLength, 0.0D, 1.0D);
				}
			}
		}
		Vec3d firstPoint = firstStart.add(firstDirection.multiply(firstFraction));
		Vec3d secondPoint = secondStart.add(secondDirection.multiply(secondFraction));
		return new SegmentDistance(firstFraction, firstPoint.squaredDistanceTo(secondPoint));
	}

	private static double pathFraction(Vec3d start, Vec3d end, Vec3d point) {
		double lengthSquared = start.squaredDistanceTo(end);
		return lengthSquared < 1.0E-8D ? 0.0D : MathHelper.clamp(
				point.subtract(start).dotProduct(end.subtract(start)) / lengthSquared, 0.0D, 1.0D);
	}

	private record SegmentDistance(double firstFraction, double distanceSquared) {
	}

	public float getSpiderWeaponRecoil(float tickDelta) {
		return MathHelper.lerp(MathHelper.clamp(tickDelta, 0.0F, 1.0F),
				previousVisualSpiderRecoil, visualSpiderRecoil);
	}

	private float trackedSpiderWeaponRecoilPulse() {
		int ticks = dataTracker.get(SPIDER_RECOIL_TICKS);
		if (ticks <= 0) {
			return 0.0F;
		}
		float elapsed = SPIDER_WEAPON_RECOIL_TICKS - ticks;
		float pulse = MathHelper.sin(MathHelper.clamp(elapsed / SPIDER_WEAPON_RECOIL_TICKS,
				0.0F, 1.0F) * (float) Math.PI);
		return pulse * MathHelper.clamp(dataTracker.get(SPIDER_RECOIL_STRENGTH) / 0.52F, 0.0F, 1.0F);
	}

	public float getSpiderBodyHealth() {
		return spiderBodyHealth;
	}

	public float getSpiderLegHealth(int index) {
		return index >= 0 && index < SPIDER_LEG_COUNT ? spiderLegHealth[index] : 0.0F;
	}

	public int getJumpModuleStrength() {
		return dataTracker.get(JUMP_MODULE_STRENGTH);
	}

	public int getDashModuleStrength() {
		return dataTracker.get(DASH_MODULE_STRENGTH);
	}

	public int getSmashModuleType() {
		return dataTracker.get(SMASH_MODULE_TYPE);
	}

	public int getClutchPhase() {
		return dataTracker.get(CLUTCH_PHASE);
	}

	private void setClutchPhase(int phase) {
		dataTracker.set(CLUTCH_PHASE, phase);
	}

	public float getInnerCartAngularSpeed() {
		return dataTracker.get(INNER_CART_ANGULAR_SPEED);
	}

	private void setInnerCartAngularSpeed(float speed) {
		dataTracker.set(INNER_CART_ANGULAR_SPEED, speed);
	}

	private float getTrackedInnerCartAngle() {
		return dataTracker.get(INNER_CART_ANGLE);
	}

	private void setInnerCartAngle(float angle) {
		dataTracker.set(INNER_CART_ANGLE, normalizeDegrees(angle));
	}

	public int getLaunchEvent() {
		return dataTracker.get(LAUNCH_EVENT);
	}

	public float getLaunchStrength() {
		return dataTracker.get(LAUNCH_STRENGTH);
	}

	public boolean isClutchAnimationActive() {
		return getClutchPhase() != CLUTCH_IDLE || Math.abs(getTrackedInnerCartAngle()) > 0.05F;
	}

	public RingVehicleVariant getVariant() {
		return RingVehicleVariant.byId(dataTracker.get(VARIANT));
	}

	public void setVariant(RingVehicleVariant variant) {
		dataTracker.set(VARIANT, variant.ordinal());
		markInventoryDirty();
	}

	public int getRingLevel() {
		return dataTracker.get(RING_LEVEL);
	}

	public void setRingLevel(int ringLevel) {
		dataTracker.set(RING_LEVEL, MathHelper.clamp(ringLevel, 0, MAX_RING_LEVEL));
		updateCollisionBounds();
	}

	public boolean isExpandedRing() {
		return getRingLevel() > 0;
	}

	public float getRingDiameter() {
		return SIZE + getRingLevel() * 2.0F;
	}

	public double getInnerCartOrbitRadius() {
		return INNER_CART_ORBIT_RADIUS + getRingLevel();
	}

	private double getCollisionOuterRadius() {
		return getRingDiameter() * 0.5D;
	}

	private double getCollisionForwardRadius() {
		return getCollisionOuterRadius() - (isExpandedRing() ? EXPANDED_COLLISION_EDGE_INSET : 0.0D);
	}

	private double getContactRadius() {
		return getCollisionOuterRadius() + 0.06D;
	}

	private double getRingRollRadius() {
		return getCollisionOuterRadius();
	}

	public boolean isLavaProof() {
		return dataTracker.get(LAVA_PROOF);
	}

	public void setLavaProof(boolean lavaProof) {
		dataTracker.set(LAVA_PROOF, lavaProof);
	}

	public boolean hasChestAttached() {
		return dataTracker.get(CHEST_ATTACHED);
	}

	public void setChestAttached(boolean attached) {
		dataTracker.set(CHEST_ATTACHED, attached);
	}

	public boolean isHighGear() {
		return dataTracker.get(HIGH_GEAR);
	}

	public void setHighGear(boolean highGear) {
		dataTracker.set(HIGH_GEAR, highGear && supportsReinforcedClutch() && hasReinforcedClutch());
	}

	public boolean supportsReinforcedClutch() {
		return getVariant().powered();
	}

	public boolean hasReinforcedClutch() {
		return dataTracker.get(CLUTCH_INSTALLED);
	}

	public boolean isMiningModeEnabled() {
		return dataTracker.get(MINING_MODE);
	}

	private void setMiningModeEnabled(boolean enabled) {
		dataTracker.set(MINING_MODE, enabled && hasMiningTool());
		if (!enabled) {
			controlMiningHeld = false;
			miningProgress.clear();
		}
	}

	public Vec3d getContactNormal() {
		Vector3f vector = dataTracker.get(CONTACT_NORMAL);
		return new Vec3d(vector.x(), vector.y(), vector.z()).normalize();
	}

	private void setContactNormal(Vec3d normal) {
		Vec3d normalized = normal.normalize();
		dataTracker.set(CONTACT_NORMAL, new Vector3f((float) normalized.x, (float) normalized.y, (float) normalized.z));
	}

	public Vec3d getForwardVector() {
		Vector3f vector = dataTracker.get(FORWARD);
		Vec3d forward = new Vec3d(vector.x(), vector.y(), vector.z());
		return forward.lengthSquared() < 0.01D ? new Vec3d(0.0D, 0.0D, 1.0D) : forward.normalize();
	}

	private void setForwardVector(Vec3d forward) {
		Vec3d normalized = forward.normalize();
		dataTracker.set(FORWARD, new Vector3f((float) normalized.x, (float) normalized.y, (float) normalized.z));
	}

	public boolean isFloatingVehicle() {
		return dataTracker.get(FLOATING);
	}

	public boolean isDiscMode() {
		return dataTracker.get(DISC_MODE);
	}

	public boolean isSpiderMode() {
		return dataTracker.get(SPIDER_MODE);
	}

	public int getSpiderExplorationMode() {
		return MathHelper.clamp(dataTracker.get(SPIDER_EXPLORATION_MODE),
				SPIDER_EXPLORATION_AUTO, SPIDER_EXPLORATION_DOWN);
	}

	public boolean isSpiderAwake() {
		return !isSpiderMode() || dataTracker.get(SPIDER_AWAKE);
	}

	public boolean isSpiderSleepTransition() {
		return isSpiderMode() && dataTracker.get(SPIDER_SLEEP_TRANSITION);
	}

	public int getSpiderInstalledLegMask() {
		return dataTracker.get(SPIDER_LEG_MASK) & SPIDER_FULL_LEG_MASK;
	}

	public int getSpiderPendingLegIndex() {
		int index = dataTracker.get(SPIDER_PENDING_LEG);
		return index >= 0 && index < SPIDER_LEG_COUNT ? index : -1;
	}

	public int getSpiderVisibleLegMask() {
		int pending = getSpiderPendingLegIndex();
		return getSpiderInstalledLegMask() | (pending < 0 ? 0 : 1 << pending);
	}

	public int getSpiderRenderedLegMask() {
		if (isSpiderAwake()) {
			return getSpiderVisibleLegMask();
		}
		int mask = 0;
		int pending = getSpiderPendingLegIndex();
		int retracting = getSpiderRetractingLegIndex();
		if (pending >= 0) {
			mask |= 1 << pending;
		}
		if (retracting >= 0) {
			mask |= 1 << retracting;
		}
		return mask;
	}

	public int getSpiderInstalledLegCount() {
		return Integer.bitCount(getSpiderInstalledLegMask());
	}

	public boolean hasSpiderLegVisual(int index) {
		return index >= 0 && index < SPIDER_LEG_COUNT
				&& (getSpiderRenderedLegMask() & 1 << index) != 0;
	}

	public boolean isSpiderLegPending(int index) {
		return index == getSpiderPendingLegIndex();
	}

	public int getSpiderRetractingLegIndex() {
		int index = dataTracker.get(SPIDER_RETRACTING_LEG);
		return index >= 0 && index < SPIDER_LEG_COUNT ? index : -1;
	}

	public boolean isSpiderLegRetracting(int index) {
		return index == getSpiderRetractingLegIndex()
				&& dataTracker.get(SPIDER_LEG_RETRACT_TICKS) > 0;
	}

	public float getSpiderLegRenderProgress(int index) {
		if (index < 0 || index >= SPIDER_LEG_COUNT) {
			return 0.0F;
		}
		if (isSpiderAwake()) {
			return isSpiderSleepTransition() ? 1.0F : getSpiderDeployProgress();
		}
		if (isSpiderLegPending(index)) {
			return 1.0F;
		}
		if (isSpiderLegRetracting(index)) {
			return MathHelper.clamp(dataTracker.get(SPIDER_LEG_RETRACT_TICKS)
					/ (float) SPIDER_LEG_RETRACT_DURATION_TICKS, 0.0F, 1.0F);
		}
		return 0.0F;
	}

	private boolean hasFunctionalSpiderLeg(int index) {
		return index >= 0 && index < SPIDER_LEG_COUNT
				&& (getSpiderInstalledLegMask() & 1 << index) != 0;
	}

	public float getSpiderDeployProgress() {
		return isSpiderMode() ? dataTracker.get(SPIDER_DEPLOY_PROGRESS) : 1.0F;
	}

	/** Item placement always starts a spider vehicle in its compact, dormant state. */
	public void prepareSpiderPlacement() {
		if (!isSpiderMode()) {
			return;
		}
		dataTracker.set(SPIDER_AWAKE, false);
		dataTracker.set(SPIDER_SLEEP_TRANSITION, false);
		dataTracker.set(SPIDER_RETRACTING_LEG, -1);
		dataTracker.set(SPIDER_LEG_RETRACT_TICKS, 0);
		dataTracker.set(SPIDER_DEPLOY_PROGRESS, 0.0F);
		spiderFeetInitialized = false;
		updateCollisionBounds();
	}

	public float getSpiderGaitPhase() {
		return dataTracker.get(SPIDER_GAIT_PHASE);
	}

	public float getSpiderSuspensionOffset(float tickDelta) {
		return MathHelper.lerp(MathHelper.clamp(tickDelta, 0.0F, 1.0F),
				previousSpiderSuspensionOffset, spiderSuspensionOffset);
	}

	public Vec3d getSpiderBodyNormal(float tickDelta) {
		Vec3d normal = previousVisualSpiderBodyNormal.lerp(visualSpiderBodyNormal,
				MathHelper.clamp(tickDelta, 0.0F, 1.0F));
		return normal.lengthSquared() < 0.5D ? Vec3d.of(Direction.UP.getVector()) : normal.normalize();
	}

	/** Returns a client-visible foot offset from the spider body's render centre. */
	public Vec3d getSpiderFootOffset(int index, float tickDelta) {
		if (index < 0 || index >= SPIDER_LEG_COUNT) {
			return Vec3d.ZERO;
		}
		Vec3d offset;
		if (!isSpiderAwake()) {
			offset = dormantSpiderFootOffset(index);
		} else if (getWorld().isClient() && visualSpiderFeetInitialized && visualSpiderFootOffsets[index] != null) {
			Vec3d previous = previousVisualSpiderFootOffsets[index] == null
					? visualSpiderFootOffsets[index]
					: previousVisualSpiderFootOffsets[index];
			offset = previous.lerp(visualSpiderFootOffsets[index], MathHelper.clamp(tickDelta, 0.0F, 1.0F));
		} else {
			Vector3f vector = dataTracker.get(SPIDER_FOOT_OFFSETS[index]);
			offset = new Vec3d(vector.x(), vector.y(), vector.z());
		}
		Vec3d localOffset = offset.rotateY((float) Math.toRadians(getVisualBodyYaw(tickDelta)));
		if (isSpiderSleepTransition()) {
			localOffset = dormantSpiderFootOffset(index).lerp(localOffset, getSpiderDeployProgress());
		}
		if (isSpiderAwake() && getSpiderDeployProgress() >= 1.0F && getVelocity().lengthSquared() < 0.006D) {
			double time = age + tickDelta;
			double phase = time * (0.052D + index * 0.0017D) + getId() * 0.31D + index * 1.91D;
			double pulse = Math.pow(Math.max(0.0D, Math.sin(phase)), 10.0D);
			double secondary = Math.sin(time * 0.033D + getId() * 0.17D + index * 0.83D);
			Vec3d radial = spiderLegRadial(new Vec3d(0.0D, 0.0D, 1.0D), index);
			Vec3d tangent = Vec3d.of(Direction.UP.getVector()).crossProduct(radial).normalize();
			localOffset = localOffset
					.add(radial.multiply(pulse * 0.030D))
					.add(tangent.multiply(pulse * secondary * 0.022D))
					.add(0.0D, pulse * 0.026D, 0.0D);
		}
		return localOffset;
	}

	private Vec3d dormantSpiderFootOffset(int index) {
		Vec3d radial = spiderLegRadial(new Vec3d(0.0D, 0.0D, 1.0D), index);
		boolean assemblyPose = isSpiderLegPending(index) || isSpiderLegRetracting(index);
		double pendingScale = assemblyPose ? 0.63D : 0.69D;
		return radial.multiply(spiderLegReach(getRingLevel()) * pendingScale)
				.add(0.0D, -getRingDiameter() * 0.5D
						+ (assemblyPose ? 0.18D : 0.04D), 0.0D);
	}

	/** Root positions are on the body's horizontal equator, where the rail belt is rendered. */
	public Vec3d getSpiderLegRootOffset(int index) {
		Vec3d localHeading = new Vec3d(0.0D, 0.0D, 1.0D);
		Vec3d radial = spiderLegRadial(localHeading, index);
		return radial.multiply(getRingDiameter() * 0.5D * SPIDER_LEG_ROOT_RADIUS_RATIO);
	}

	public double getSpiderLegReach() {
		return spiderLegReach(getRingLevel());
	}

	public boolean isMomentumStorageEnabled() {
		return dataTracker.get(MOMENTUM_STORAGE_ENABLED);
	}

	private void setMomentumStorageEnabled(boolean enabled) {
		dataTracker.set(MOMENTUM_STORAGE_ENABLED, enabled);
	}

	public float getMomentumStorageCharge() {
		return momentumStorageCharge;
	}

	private void setDiscMode(boolean discMode) {
		dataTracker.set(DISC_MODE, discMode);
		if (discMode) {
			dataTracker.set(SPIDER_MODE, false);
		}
		if (!discMode) {
			setFlightRotorSpeed(0.0F);
			momentumReleaseActive = false;
		}
		updateCollisionBounds();
	}

	public int getDiscExtraMinecarts() {
		return dataTracker.get(DISC_EXTRA_MINECARTS);
	}

	private void setDiscExtraMinecarts(int count) {
		dataTracker.set(DISC_EXTRA_MINECARTS, MathHelper.clamp(count, 0, MAX_DISC_EXTRA_MINECARTS));
	}

	public float getFlightRotorSpeed() {
		return dataTracker.get(FLIGHT_ROTOR_SPEED);
	}

	private void setFlightRotorSpeed(float speed) {
		dataTracker.set(FLIGHT_ROTOR_SPEED, Float.isFinite(speed) ? speed : 0.0F);
	}

	public boolean isDiscFlightActive() {
		return dataTracker.get(DISC_FLIGHT_ACTIVE);
	}

	private void setDiscFlightActive(boolean active) {
		dataTracker.set(DISC_FLIGHT_ACTIVE, active);
	}

	public double getRenderCenterHeight() {
		if (isDiscMode()) {
			return DISC_RENDER_CENTER_HEIGHT;
		}
		return isSpiderMode()
				? spiderBodyCenterHeight(trackedSpiderBodyNormal(), getSpiderDeployProgress())
				: getRingDiameter() * 0.5D;
	}

	public float getDiscVisualBlend(float tickDelta) {
		return MathHelper.lerp(tickDelta, previousDiscVisualBlend, discVisualBlend);
	}

	public double getVisualRenderCenterHeight(float tickDelta) {
		if (isSpiderMode()) {
			return spiderBodyCenterHeight(getSpiderBodyNormal(tickDelta), getSpiderDeployProgress());
		}
		return MathHelper.lerp(getDiscVisualBlend(tickDelta), getRingDiameter() * 0.5D, DISC_RENDER_CENTER_HEIGHT);
	}

	private double spiderBodyCenterHeight(Vec3d normal, float deployProgress) {
		double radius = getRingDiameter() * 0.5D;
		double deployment = MathHelper.clamp(deployProgress, 0.0F, 1.0F);
		if (!isSpiderAwake() || deployment <= 0.0D) {
			return radius;
		}
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double halfThickness = 0.42D + getRingLevel() * 0.08D;
		double verticalRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.y * safeNormal.y))
				+ halfThickness * Math.abs(safeNormal.y);
		double draggedHeight = verticalRadius + 0.055D;
		double supportedHeight = radius + SPIDER_BODY_LIFT;
		double supportBlend = MathHelper.clamp((getSpiderInstalledLegCount() - 1.0D) / 3.0D, 0.0D, 1.0D);
		double deployedHeight = MathHelper.lerp(supportBlend, draggedHeight, supportedHeight);
		return MathHelper.lerp(deployment, radius, deployedHeight);
	}

	public Vec3d getDiscVisualShakeOffset(float tickDelta) {
		if (!isDiscMode()) {
			return Vec3d.ZERO;
		}
		double time = age + tickDelta;
		Vec3d offset = Vec3d.ZERO;
		if (!isMomentumStorageEnabled()
				&& getClutchPhase() == CLUTCH_SPINNING && getInnerCartAngularSpeed() > 0.0F) {
			double ratio = MathHelper.clamp(getInnerCartAngularSpeed() / maximumClutchAngularSpeed(), 0.0D, 1.0D);
			double rampIn = MathHelper.clamp((ratio - 0.42D) / 0.20D, 0.0D, 1.0D);
			double rampOut = 1.0D - MathHelper.clamp((ratio - 0.82D) / 0.16D, 0.0D, 1.0D);
			double strength = rampIn * rampOut;
			offset = new Vec3d(
					Math.sin(time * 2.73D + getId() * 0.31D) * 0.034D * strength,
					Math.sin(time * 3.91D + getId() * 0.53D) * 0.018D * strength,
					Math.cos(time * 3.37D + getId() * 0.47D) * 0.032D * strength);
		}
		if (isDiscFlightActive()) {
			double phase = getId() * 0.37D;
			Vec3d flightWobble = new Vec3d(
					(Math.sin(time * 0.29D + phase) + Math.sin(time * 0.13D + phase * 1.7D) * 0.55D) * 0.0085D,
					(Math.sin(time * 0.23D + phase * 2.1D) + Math.cos(time * 0.11D + phase) * 0.45D) * 0.0048D,
					(Math.cos(time * 0.31D + phase * 0.8D) + Math.sin(time * 0.17D + phase * 1.3D) * 0.50D) * 0.0085D);
			offset = offset.add(flightWobble);
		}
		return offset;
	}

	public float getVisualRingAngle(float tickDelta) {
		return (float) MathHelper.lerpAngleDegrees(tickDelta, previousVisualRingAngle, visualRingAngle);
	}

	public float getVisualInnerCartAngle(float tickDelta) {
		return (float) MathHelper.lerpAngleDegrees(tickDelta, previousVisualInnerCartAngle, visualInnerCartAngle);
	}

	public float getVisualBodyYaw(float tickDelta) {
		return (float) MathHelper.lerpAngleDegrees(tickDelta, previousVisualBodyYaw, visualBodyYaw);
	}

	public Vec3d getVisualPassengerAnchor(float tickDelta) {
		if (isSpiderMode()) {
			Vec3d center = getLerpedPos(tickDelta).add(0.0D,
					getVisualRenderCenterHeight(tickDelta) + getSpiderBodyBob(tickDelta), 0.0D);
			return center.add(getSpiderBodyNormal(tickDelta).multiply(
					SPIDER_SEAT_OFFSET + getSpiderSuspensionOffset(tickDelta)));
		}
		float angle = getVisualInnerCartAngle(tickDelta);
		double orbitRadians = Math.toRadians(angle);
		double leanRadians = Math.toRadians(getTurnVisualLean(tickDelta));
		double orbitRadius = getInnerCartOrbitRadius() + PASSENGER_RENDER_SEAT_DEPTH;
		Vec3d heading = bodyHeading(getVisualBodyYaw(tickDelta));
		Vec3d worldUp = Vec3d.of(Direction.UP.getVector());
		Vec3d side = worldUp.crossProduct(heading).normalize();
		if (isDiscMode()) {
			double discSeatRadius = getInnerCartOrbitRadius() + DISC_PASSENGER_SEAT_DEPTH;
			return getLerpedPos(tickDelta)
					.add(worldUp.multiply(DISC_RENDER_CENTER_HEIGHT + DISC_CART_VERTICAL_OFFSET - 0.08D))
					.add(side.multiply(discSeatRadius * Math.cos(orbitRadians)))
					.add(heading.multiply(-discSeatRadius * Math.sin(orbitRadians)))
					.add(getDiscVisualShakeOffset(tickDelta));
		}
		double localY = -orbitRadius * Math.cos(orbitRadians);
		double localForward = -orbitRadius * Math.sin(orbitRadians);
		double localSide = -localY * Math.sin(leanRadians);
		double leanedY = localY * Math.cos(leanRadians);
		return getLerpedPos(tickDelta)
				.add(worldUp.multiply(getRingDiameter() * 0.5D + getRideVisualBob(tickDelta) + leanedY))
				.add(side.multiply(localSide))
				.add(heading.multiply(localForward));
	}

	public float getWaterVisualBob(float tickDelta) {
		float blend = MathHelper.lerp(tickDelta, previousFloatingVisualBlend, floatingVisualBlend);
		float time = age + tickDelta;
		float primary = MathHelper.sin(time * 0.061F + getId() * 0.17F) * 0.026F;
		float secondary = MathHelper.sin(time * 0.037F + getId() * 0.43F) * 0.012F;
		return (primary + secondary) * blend;
	}

	public float getUphillVisualBob(float tickDelta) {
		double speed = getVelocity().length();
		double ascent = getVelocity().y;
		if (speed < 0.04D || ascent < 0.025D || isFloatingVehicle()) {
			return 0.0F;
		}
		float strength = (float) MathHelper.clamp(ascent / 0.38D, 0.0D, 1.0D)
				* (float) MathHelper.clamp(speed / 0.55D, 0.0D, 1.0D);
		float time = age + tickDelta;
		float uneven = MathHelper.sin(time * 0.57F + getId() * 0.31F) * 0.65F
				+ MathHelper.sin(time * 0.91F + getId() * 0.73F) * 0.35F;
		return uneven * 0.018F * strength;
	}

	public float getRideVisualBob(float tickDelta) {
		if (isDiscMode()) {
			return 0.0F;
		}
		if (isSpiderMode()) {
			return getSpiderBodyBob(tickDelta);
		}
		return getWaterVisualBob(tickDelta) + getUphillVisualBob(tickDelta);
	}

	private float getSpiderBodyBob(float tickDelta) {
		float speed = (float) getVelocity().length();
		float movementBlend = MathHelper.clamp(speed / 0.42F, 0.0F, 1.0F);
		float idleBlend = isSpiderAwake() && getSpiderDeployProgress() >= 1.0F
				? 1.0F - MathHelper.clamp(speed / 0.075F, 0.0F, 1.0F)
				: 0.0F;
		float time = age + tickDelta;
		float uneven = MathHelper.sin(time * 0.47F + getId() * 0.37F) * 0.62F
				+ MathHelper.sin(time * 0.73F + getId() * 0.81F) * 0.38F;
		float idleBreathing = MathHelper.sin(time * 0.071F + getId() * 0.29F) * 0.70F
				+ MathHelper.sin(time * 0.039F + getId() * 0.61F) * 0.30F;
		float compression = dataTracker.get(SPIDER_BODY_COMPRESSION);
		return uneven * 0.045F * movementBlend + idleBreathing * 0.018F * idleBlend
				+ getWaterVisualBob(tickDelta) * 0.55F
				- compression * 0.20F;
	}

	public float getTurnVisualLean(float tickDelta) {
		if (isDiscMode()) {
			return 0.0F;
		}
		return MathHelper.lerp(tickDelta, previousTurnVisualLean, turnVisualLean);
	}

	@Override
	public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps) {
		if (!getWorld().isClient()) {
			super.updateTrackedPositionAndAngles(x, y, z, yaw, pitch, interpolationSteps);
			return;
		}
		if (getPos().squaredDistanceTo(x, y, z) > 36.0D) {
			setPosition(x, y, z);
			setYaw(yaw);
			setPitch(pitch);
			clientLerpTicks = 0;
			return;
		}
		clientTargetX = x;
		clientTargetY = y;
		clientTargetZ = z;
		clientTargetYaw = yaw;
		clientTargetPitch = pitch;
		double correctionSquared = getPos().squaredDistanceTo(x, y, z);
		double speedSquared = getVelocity().lengthSquared();
		clientLerpTicks = correctionSquared > 0.04D || speedSquared > 0.16D ? 1 : 2;
	}

	@Override
	public double getLerpTargetX() {
		return clientLerpTicks > 0 ? clientTargetX : getX();
	}

	@Override
	public double getLerpTargetY() {
		return clientLerpTicks > 0 ? clientTargetY : getY();
	}

	@Override
	public double getLerpTargetZ() {
		return clientLerpTicks > 0 ? clientTargetZ : getZ();
	}

	@Override
	public float getLerpTargetYaw() {
		return clientLerpTicks > 0 ? clientTargetYaw : getYaw();
	}

	@Override
	public float getLerpTargetPitch() {
		return clientLerpTicks > 0 ? clientTargetPitch : getPitch();
	}

	private void tickClientInterpolation() {
		if (clientLerpTicks <= 0) {
			return;
		}
		double progress = 1.0D / clientLerpTicks;
		double x = MathHelper.lerp(progress, getX(), clientTargetX);
		double y = MathHelper.lerp(progress, getY(), clientTargetY);
		double z = MathHelper.lerp(progress, getZ(), clientTargetZ);
		float yaw = (float) MathHelper.lerpAngleDegrees(progress, getYaw(), clientTargetYaw);
		float pitch = MathHelper.lerp((float) progress, getPitch(), clientTargetPitch);
		setPosition(x, y, z);
		setYaw(yaw);
		setPitch(pitch);
		clientLerpTicks--;
	}

	/** Maintains previous/current render snapshots independently from position interpolation. */
	private void updateVisualState() {
		updateSpiderSuspensionVisual();
		previousVisualSpiderRecoil = visualSpiderRecoil;
		float recoilTarget = isSpiderMode() ? trackedSpiderWeaponRecoilPulse() : 0.0F;
		visualSpiderRecoilVelocity += (recoilTarget - visualSpiderRecoil) * 0.44F;
		visualSpiderRecoilVelocity *= 0.56F;
		visualSpiderRecoil = MathHelper.clamp(
				visualSpiderRecoil + visualSpiderRecoilVelocity, -0.08F, 1.18F);
		if (recoilTarget == 0.0F && Math.abs(visualSpiderRecoil) < 0.002F
				&& Math.abs(visualSpiderRecoilVelocity) < 0.002F) {
			visualSpiderRecoil = 0.0F;
			visualSpiderRecoilVelocity = 0.0F;
		}
		if (isSpiderMode()) {
			previousVisualSpiderBodyNormal = visualSpiderBodyNormal;
			Vec3d trackedNormal = trackedSpiderBodyNormal();
			Vec3d blendedNormal = visualSpiderBodyNormal.lerp(trackedNormal, 0.42D);
			visualSpiderBodyNormal = blendedNormal.lengthSquared() < 0.5D
					? trackedNormal
					: blendedNormal.normalize();
			for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
				Vector3f tracked = dataTracker.get(SPIDER_FOOT_OFFSETS[index]);
				Vec3d target = new Vec3d(tracked.x(), tracked.y(), tracked.z());
				if (!visualSpiderFeetInitialized || visualSpiderFootOffsets[index] == null) {
					previousVisualSpiderFootOffsets[index] = target;
					visualSpiderFootOffsets[index] = target;
				} else {
					previousVisualSpiderFootOffsets[index] = visualSpiderFootOffsets[index];
					visualSpiderFootOffsets[index] = approachSpiderFootVisual(
							visualSpiderFootOffsets[index], target);
				}
			}
			visualSpiderFeetInitialized = true;
		} else {
			visualSpiderFeetInitialized = false;
			previousVisualSpiderBodyNormal = Vec3d.of(Direction.UP.getVector());
			visualSpiderBodyNormal = Vec3d.of(Direction.UP.getVector());
		}
		previousVisualRingAngle = visualRingAngle;
		previousVisualInnerCartAngle = visualInnerCartAngle;
		previousDiscVisualBlend = discVisualBlend;
		// An unoccupied hovering aircraft lets its cockpit orbit with the powered outer ring.
		if (isDiscFlightActive() && getPassengerList().isEmpty()) {
			visualInnerCartAngle = MathHelper.wrapDegrees(visualInnerCartAngle + getFlightRotorSpeed());
		} else {
			visualInnerCartAngle = getTrackedInnerCartAngle();
		}
		discVisualBlend = (float) approach(discVisualBlend, isDiscMode() ? 1.0D : 0.0D, 0.11D);
		previousFloatingVisualBlend = floatingVisualBlend;
		previousTurnVisualLean = turnVisualLean;
		floatingVisualBlend = (float) approach(floatingVisualBlend, isFloatingVehicle() ? 1.0D : 0.0D, 0.08D);
		if (!visualYawInitialized) {
			lastVisualYaw = getYaw();
			previousVisualBodyYaw = getYaw();
			visualBodyYaw = getYaw();
			visualYawInitialized = true;
		} else {
			previousVisualBodyYaw = visualBodyYaw;
			visualBodyYaw = getYaw();
		}
		float yawDelta = MathHelper.wrapDegrees(getYaw() - lastVisualYaw);
		float leanTarget = MathHelper.clamp(yawDelta * 0.9F, -4.0F, 4.0F);
		turnVisualLean += (leanTarget - turnVisualLean) * 0.28F;
		if (Math.abs(leanTarget) < 0.05F) {
			turnVisualLean *= 0.82F;
		}
		lastVisualYaw = getYaw();
		Vec3d currentVisualPosition = getPos();
		if (isDiscMode()) {
			visualRingAngle = MathHelper.wrapDegrees(visualRingAngle + getFlightRotorSpeed());
		} else if (lastVisualPosition.lengthSquared() > 0.0D) {
			Vec3d displacement = currentVisualPosition.subtract(lastVisualPosition);
			double signedDistance = displacement.dotProduct(getForwardVector());
			if (Math.abs(signedDistance) < 4.0D && displacement.lengthSquared() < 16.0D) {
				visualRingAngle = MathHelper.wrapDegrees(visualRingAngle
						+ (float) Math.toDegrees(signedDistance / getRingRollRadius()));
			}
			if (displacement.lengthSquared() >= 16.0D) {
				previousVisualRingAngle = visualRingAngle;
			}
		}
		lastVisualPosition = currentVisualPosition;
	}

	/** Smooths network corrections without allowing a retargeted foot to teleport across the body. */
	private static Vec3d approachSpiderFootVisual(Vec3d current, Vec3d target) {
		Vec3d delta = target.subtract(current);
		double distance = delta.length();
		if (distance < 1.0E-5D) {
			return target;
		}
		double step = Math.min(distance,
				Math.min(0.48D, Math.max(0.075D, distance * 0.68D)));
		return current.add(delta.multiply(step / distance));
	}

	/** Integrates a small gravity-biased spring for the cable-suspended cockpit. */
	private void updateSpiderSuspensionVisual() {
		previousSpiderSuspensionOffset = spiderSuspensionOffset;
		if (!isSpiderMode() || !isSpiderAwake() || getSpiderDeployProgress() < 1.0F) {
			spiderSuspensionOffset = (float) approach(
					spiderSuspensionOffset, SPIDER_SUSPENSION_REST_OFFSET, 0.035D);
			spiderSuspensionVelocity *= 0.60F;
			previousSpiderSuspensionVerticalSpeed = getVelocity().y;
			return;
		}

		double verticalSpeed = getVelocity().y;
		double verticalAcceleration = MathHelper.clamp(
				verticalSpeed - previousSpiderSuspensionVerticalSpeed, -0.30D, 0.30D);
		previousSpiderSuspensionVerticalSpeed = verticalSpeed;
		float speedBlend = (float) MathHelper.clamp(getVelocity().length() / 0.52D, 0.0D, 1.0D);
		float gaitResponse = MathHelper.sin(getSpiderGaitPhase() * MathHelper.TAU * 2.0F + getId() * 0.31F)
				* 0.011F * speedBlend;
		float compressionResponse = dataTracker.get(SPIDER_BODY_COMPRESSION) * 0.045F;
		float target = SPIDER_SUSPENSION_REST_OFFSET
				- (float) verticalAcceleration * 0.42F + gaitResponse + compressionResponse;

		spiderSuspensionVelocity += (target - spiderSuspensionOffset) * 0.16F;
		spiderSuspensionVelocity *= 0.79F;
		spiderSuspensionOffset += spiderSuspensionVelocity;
		float clamped = MathHelper.clamp(spiderSuspensionOffset, -0.19F, 0.085F);
		if (clamped != spiderSuspensionOffset) {
			spiderSuspensionOffset = clamped;
			spiderSuspensionVelocity *= -0.18F;
		}
	}

	private Vec3d center() {
		return getPos().add(0.0D, getRenderCenterHeight(), 0.0D);
	}

	public Vec3d getVisualCenter(float tickDelta) {
		return getLerpedPos(tickDelta).add(0.0D,
				getVisualRenderCenterHeight(tickDelta) + getRideVisualBob(tickDelta), 0.0D);
	}

	private Vec3d getBodyHeading() {
		return bodyHeading(getYaw());
	}

	private static Vec3d bodyHeading(float yaw) {
		double radians = Math.toRadians(yaw);
		return new Vec3d(-Math.sin(radians), 0.0D, Math.cos(radians));
	}

	/** Rebuilds the world-space box after mode, heading, contact normal, or ring level changes. */
	private void updateCollisionBounds() {
		Box targetBounds = isDiscMode()
				? discCollisionBounds(getPos(), getRingLevel())
				: isSpiderMode()
						? currentSpiderCollisionBounds()
						: collisionBounds(getPos(), Vec3d.of(Direction.UP.getVector()), getBodyHeading(), getRingLevel());
		if (isSpiderMode() && isSpiderAwake() && getSpiderDeployProgress() >= 1.0F
				&& !getWorld().isSpaceEmpty(this, targetBounds.contract(0.025D))) {
			return;
		}
		setBoundingBox(targetBounds);
	}

	private Box currentSpiderCollisionBounds() {
		int safeLevel = MathHelper.clamp(getRingLevel(), 0, MAX_RING_LEVEL);
		double radius = (SIZE + safeLevel * 2.0D) * 0.5D;
		double deployment = MathHelper.clamp(getSpiderDeployProgress(), 0.0F, 1.0F);
		Vec3d safeNormal = trackedSpiderBodyNormal();
		double sleepingHalfThickness = 0.58D + safeLevel * 0.08D;
		double deployedHalfThickness = 0.42D + safeLevel * 0.08D;
		double halfThickness = MathHelper.lerp(deployment, sleepingHalfThickness, deployedHalfThickness);
		Vec3d center = getPos().add(0.0D, spiderBodyCenterHeight(safeNormal, getSpiderDeployProgress()), 0.0D);
		double xRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.x * safeNormal.x))
				+ halfThickness * Math.abs(safeNormal.x);
		double yRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.y * safeNormal.y))
				+ halfThickness * Math.abs(safeNormal.y);
		double zRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.z * safeNormal.z))
				+ halfThickness * Math.abs(safeNormal.z);
		return new Box(center.x - xRadius, center.y - yRadius, center.z - zRadius,
				center.x + xRadius, center.y + yRadius, center.z + zRadius);
	}

	public static Box spiderCollisionBounds(Vec3d anchor, int ringLevel) {
		return spiderCollisionBounds(anchor, ringLevel, 0.0F, Vec3d.of(Direction.UP.getVector()));
	}

	private static Box spiderCollisionBounds(Vec3d anchor, int ringLevel, float deployProgress, Vec3d normal) {
		int safeLevel = MathHelper.clamp(ringLevel, 0, MAX_RING_LEVEL);
		double radius = (SIZE + safeLevel * 2.0D) * 0.5D;
		double deployment = MathHelper.clamp(deployProgress, 0.0F, 1.0F);
		double lift = SPIDER_BODY_LIFT * deployment;
		Vec3d center = anchor.add(0.0D, radius + lift, 0.0D);
		Vec3d safeNormal = normal.lengthSquared() < 0.5D
				? Vec3d.of(Direction.UP.getVector())
				: normal.normalize();
		double sleepingHalfThickness = 0.58D + safeLevel * 0.08D;
		double deployedHalfThickness = 0.42D + safeLevel * 0.08D;
		double halfThickness = MathHelper.lerp(deployment, sleepingHalfThickness, deployedHalfThickness);
		double xRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.x * safeNormal.x))
				+ halfThickness * Math.abs(safeNormal.x);
		double yRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.y * safeNormal.y))
				+ halfThickness * Math.abs(safeNormal.y);
		double zRadius = radius * Math.sqrt(Math.max(0.0D, 1.0D - safeNormal.z * safeNormal.z))
				+ halfThickness * Math.abs(safeNormal.z);
		return new Box(center.x - xRadius, center.y - yRadius, center.z - zRadius,
				center.x + xRadius, center.y + yRadius, center.z + zRadius);
	}

	public static double spiderLegReach(int ringLevel) {
		int safeLevel = MathHelper.clamp(ringLevel, 0, MAX_RING_LEVEL);
		return (SIZE + safeLevel * 2.0D) * 0.5D * SPIDER_LEG_REACH_RATIO;
	}

	public static double spiderLegMaximumReach(int ringLevel) {
		return spiderLegReach(ringLevel) * SPIDER_LEG_ELASTIC_EXTENSION;
	}

	public static Box discCollisionBounds(Vec3d anchor, int ringLevel) {
		int safeLevel = MathHelper.clamp(ringLevel, 0, MAX_RING_LEVEL);
		double radius = (SIZE + safeLevel * 2.0D) * 0.5D;
		return new Box(anchor.x - radius, anchor.y, anchor.z - radius,
				anchor.x + radius, anchor.y + DISC_COLLISION_HEIGHT, anchor.z + radius);
	}

	private Box visualBounds(Vec3d anchor, Vec3d forward) {
		Vec3d safeForward = new Vec3d(forward.x, 0.0D, forward.z).normalize();
		Vec3d side = Vec3d.of(Direction.UP.getVector()).crossProduct(safeForward).normalize();
		double diameter = getRingDiameter();
		Vec3d center = anchor.add(0.0D, diameter * 0.5D, 0.0D);
		double horizontalForward = getCollisionForwardRadius();
		double horizontalSide = COLLISION_HALF_THICKNESS;
		double xRadius = Math.abs(safeForward.x) * horizontalForward + Math.abs(side.x) * horizontalSide;
		double zRadius = Math.abs(safeForward.z) * horizontalForward + Math.abs(side.z) * horizontalSide;
		return new Box(center.x - xRadius, anchor.y, center.z - zRadius,
				center.x + xRadius, anchor.y + diameter, center.z + zRadius);
	}

	public static Box collisionBounds(Vec3d anchor, Vec3d normal, Vec3d forward) {
		return collisionBounds(anchor, normal, forward, 0);
	}

	public static Box collisionBounds(Vec3d anchor, Vec3d normal, Vec3d forward, int ringLevel) {
		int safeLevel = MathHelper.clamp(ringLevel, 0, MAX_RING_LEVEL);
		double diameter = SIZE + safeLevel * 2.0D;
		double normalOuterRadius = diameter * 0.5D;
		double forwardOuterRadius = normalOuterRadius - safeLevel * EXPANDED_COLLISION_EDGE_INSET;
		double normalCornerRadius = COLLISION_CORNER_RADIUS * diameter / SIZE;
		double forwardCornerRadius = normalCornerRadius - safeLevel * 0.05D;
		Vec3d safeNormal = normal.lengthSquared() < 0.5D ? Vec3d.of(Direction.UP.getVector()) : normal.normalize();
		Vec3d safeForward = projectOntoPlane(forward, safeNormal);
		if (safeForward.lengthSquared() < 0.01D) {
			safeForward = Math.abs(safeNormal.y) > 0.9D
					? Vec3d.of(Direction.SOUTH.getVector())
					: safeNormal.crossProduct(Vec3d.of(Direction.UP.getVector()));
		}
		safeForward = safeForward.normalize();
		if (Math.abs(safeNormal.y) > 0.9D) {
			safeForward = Math.abs(safeForward.x) > Math.abs(safeForward.z)
					? new Vec3d(Math.copySign(1.0D, safeForward.x), 0.0D, 0.0D)
					: new Vec3d(0.0D, 0.0D, Math.copySign(1.0D, safeForward.z));
		}
		Vec3d side = safeNormal.crossProduct(safeForward).normalize();
		Vec3d center = anchor.add(0.0D, diameter * 0.5D, 0.0D);
		double[][] octagon = {
				{ forwardCornerRadius, normalOuterRadius },
				{ forwardOuterRadius, normalCornerRadius },
				{ forwardOuterRadius, -normalCornerRadius },
				{ forwardCornerRadius, -normalOuterRadius },
				{ -forwardCornerRadius, -normalOuterRadius },
				{ -forwardOuterRadius, -normalCornerRadius },
				{ -forwardOuterRadius, normalCornerRadius },
				{ -forwardCornerRadius, normalOuterRadius }
		};
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (double[] vertex : octagon) {
			for (double thickness : new double[]{ -COLLISION_HALF_THICKNESS, COLLISION_HALF_THICKNESS }) {
				Vec3d point = center
						.add(safeForward.multiply(vertex[0]))
						.add(safeNormal.multiply(vertex[1]))
						.add(side.multiply(thickness));
				minX = Math.min(minX, point.x);
				minY = Math.min(minY, point.y);
				minZ = Math.min(minZ, point.z);
				maxX = Math.max(maxX, point.x);
				maxY = Math.max(maxY, point.y);
				maxZ = Math.max(maxZ, point.z);
			}
		}
		return new Box(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static Vec3d projectOntoPlane(Vec3d vector, Vec3d normal) {
		return vector.subtract(normal.multiply(vector.dotProduct(normal)));
	}

	private static Vec3d rotateAroundAxis(Vec3d vector, Vec3d axis, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return vector.multiply(cos)
				.add(axis.crossProduct(vector).multiply(sin))
				.add(axis.multiply(axis.dotProduct(vector) * (1.0D - cos)));
	}

	private static Direction dominantDirection(Vec3d vector) {
		double x = Math.abs(vector.x);
		double y = Math.abs(vector.y);
		double z = Math.abs(vector.z);
		if (y >= x && y >= z) {
			return vector.y >= 0.0D ? Direction.UP : Direction.DOWN;
		}
		if (x >= z) {
			return vector.x >= 0.0D ? Direction.EAST : Direction.WEST;
		}
		return vector.z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
	}

	private static double approach(double value, double target, double amount) {
		if (value < target) {
			return Math.min(target, value + amount);
		}
		return Math.max(target, value - amount);
	}

	public ItemStack toItemStack() {
		ItemStack stack = new ItemStack(EchoMinecartRegistry.ringVehicleItem(getVariant(), isLavaProof()));
		NbtCompound data = new NbtCompound();
		writeItemData(data);
		stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
		return stack;
	}

	/** Writes portable state shared by entity persistence and the dropped vehicle item. */
	private void writeItemData(NbtCompound nbt) {
		nbt.putBoolean("ChestAttached", hasChestAttached());
		nbt.putBoolean("HighGear", isHighGear());
		nbt.putInt("RingLevel", getRingLevel());
		nbt.putBoolean("SpiderMode", isSpiderMode());
		nbt.putInt("SpiderLegMask", getSpiderInstalledLegMask());
		nbt.putInt("SpiderPendingLeg", getSpiderPendingLegIndex());
		nbt.putInt("SpiderExplorationMode", getSpiderExplorationMode());
		nbt.putFloat("SpiderBodyHealth", spiderBodyHealth);
		for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
			nbt.putFloat("SpiderLegHealth" + index, spiderLegHealth[index]);
		}
		nbt.putBoolean("DiscMode", isDiscMode());
		nbt.putInt("DiscExtraMinecarts", getDiscExtraMinecarts());
		nbt.putFloat("FlightRotorSpeed", getFlightRotorSpeed());
		nbt.putBoolean("DiscFlightActive", isDiscFlightActive());
		nbt.putBoolean("MomentumStorageEnabled", isMomentumStorageEnabled());
		nbt.putFloat("MomentumStorageCharge", momentumStorageCharge);
		nbt.putInt("InventoryVersion", RingVehicleInventory.DATA_VERSION);
		Inventories.writeNbt(nbt, inventory.stacks(), getRegistryManager());
	}

	public void readItemData(NbtCompound nbt) {
		setRingLevel(nbt.getInt("RingLevel"));
		dataTracker.set(SPIDER_MODE, nbt.getBoolean("SpiderMode"));
		dataTracker.set(SPIDER_SLEEP_TRANSITION, false);
		dataTracker.set(SPIDER_RETRACTING_LEG, -1);
		dataTracker.set(SPIDER_LEG_RETRACT_TICKS, 0);
		dataTracker.set(SPIDER_EXPLORATION_MODE, nbt.contains("SpiderExplorationMode")
				? MathHelper.clamp(nbt.getInt("SpiderExplorationMode"),
						SPIDER_EXPLORATION_AUTO, SPIDER_EXPLORATION_DOWN)
				: SPIDER_EXPLORATION_AUTO);
		if (isSpiderMode()) {
			int legMask = nbt.contains("SpiderLegMask")
					? nbt.getInt("SpiderLegMask") & SPIDER_FULL_LEG_MASK
					: SPIDER_FULL_LEG_MASK;
			int pendingLeg = nbt.contains("SpiderPendingLeg") ? nbt.getInt("SpiderPendingLeg") : -1;
			if (pendingLeg < 0 || pendingLeg >= SPIDER_LEG_COUNT || (legMask & 1 << pendingLeg) != 0) {
				pendingLeg = -1;
			}
			dataTracker.set(SPIDER_LEG_MASK, legMask);
			dataTracker.set(SPIDER_PENDING_LEG, pendingLeg);
			spiderBodyHealth = nbt.contains("SpiderBodyHealth")
					? MathHelper.clamp(nbt.getFloat("SpiderBodyHealth"), 1.0F, SPIDER_BODY_MAX_HEALTH)
					: SPIDER_BODY_MAX_HEALTH;
			for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
				spiderLegHealth[index] = (legMask & 1 << index) != 0 && nbt.contains("SpiderLegHealth" + index)
						? MathHelper.clamp(nbt.getFloat("SpiderLegHealth" + index), 1.0F, SPIDER_LEG_MAX_HEALTH)
						: SPIDER_LEG_MAX_HEALTH;
			}
		} else {
			dataTracker.set(SPIDER_LEG_MASK, 0);
			dataTracker.set(SPIDER_PENDING_LEG, -1);
			spiderBodyHealth = SPIDER_BODY_MAX_HEALTH;
			for (int index = 0; index < SPIDER_LEG_COUNT; index++) {
				spiderLegHealth[index] = SPIDER_LEG_MAX_HEALTH;
			}
		}
		setDiscMode(nbt.getBoolean("DiscMode") && !isSpiderMode());
		setDiscExtraMinecarts(nbt.getInt("DiscExtraMinecarts"));
		setFlightRotorSpeed(nbt.getFloat("FlightRotorSpeed"));
		setDiscFlightActive(nbt.getBoolean("DiscFlightActive"));
		setMomentumStorageEnabled(nbt.getBoolean("MomentumStorageEnabled"));
		momentumStorageCharge = MathHelper.clamp(nbt.getFloat("MomentumStorageCharge"), 0.0F, 1.0F);
		momentumReleaseActive = false;
		inventory.readNbt(nbt, getRegistryManager());
		pendingMigrationDrop = inventory.takeMigrationOverflow();
		markInventoryDirty();
		setHighGear(nbt.getBoolean("HighGear"));
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound nbt) {
		setVariant(RingVehicleVariant.byId(nbt.getInt("Variant")));
		setLavaProof(nbt.getBoolean("LavaProof"));
		readItemData(nbt);
		if (isSpiderMode()) {
			boolean sleepTransition = nbt.getBoolean("SpiderSleepTransition");
			boolean awake = sleepTransition
					|| !nbt.contains("SpiderAwake") || nbt.getBoolean("SpiderAwake");
			dataTracker.set(SPIDER_AWAKE, awake);
			dataTracker.set(SPIDER_SLEEP_TRANSITION, sleepTransition);
			dataTracker.set(SPIDER_DEPLOY_PROGRESS, awake
					? MathHelper.clamp(nbt.getFloat("SpiderDeployProgress"), 0.0F, 1.0F)
					: 0.0F);
			if (awake && !nbt.contains("SpiderDeployProgress")) {
				dataTracker.set(SPIDER_DEPLOY_PROGRESS, 1.0F);
			}
		}
		if (nbt.contains("ContactNormal")) {
			setContactNormal(vectorFromNbt(nbt.getCompound("ContactNormal")));
		}
		if (nbt.contains("Forward")) {
			setForwardVector(vectorFromNbt(nbt.getCompound("Forward")));
		}
		momentumReleaseActive = nbt.getBoolean("MomentumReleaseActive")
				&& isDiscMode() && momentumStorageCharge > 0.0001F;
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putInt("Variant", getVariant().ordinal());
		nbt.putBoolean("LavaProof", isLavaProof());
		writeItemData(nbt);
		nbt.putBoolean("MomentumReleaseActive", momentumReleaseActive);
		nbt.putBoolean("SpiderAwake", isSpiderAwake());
		nbt.putBoolean("SpiderSleepTransition", isSpiderSleepTransition());
		nbt.putFloat("SpiderDeployProgress", getSpiderDeployProgress());
		nbt.put("ContactNormal", vectorToNbt(getContactNormal()));
		nbt.put("Forward", vectorToNbt(getForwardVector()));
	}

	private static NbtCompound vectorToNbt(Vec3d vector) {
		NbtCompound nbt = new NbtCompound();
		nbt.putDouble("X", vector.x);
		nbt.putDouble("Y", vector.y);
		nbt.putDouble("Z", vector.z);
		return nbt;
	}

	private static Vec3d vectorFromNbt(NbtCompound nbt) {
		Vec3d vector = new Vec3d(nbt.getDouble("X"), nbt.getDouble("Y"), nbt.getDouble("Z"));
		return vector.lengthSquared() < 0.5D ? Vec3d.of(Direction.UP.getVector()) : vector.normalize();
	}
}
