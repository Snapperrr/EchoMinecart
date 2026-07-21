package com.xc.echominecart.ringvehicle;

import com.xc.echominecart.EchoMinecartRegistry;
import com.xc.echominecart.screen.RingVehicleScreenHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
	private static final double DISC_LIFT_THRESHOLD = 0.62D;
	private static final double DISC_STALL_THRESHOLD = 0.56D;
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
	private static final TrackedData<Integer> DISC_EXTRA_MINECARTS = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
	private static final TrackedData<Float> FLIGHT_ROTOR_SPEED = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Boolean> DISC_FLIGHT_ACTIVE = DataTracker.registerData(RingVehicleEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
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

	// Authoritative gameplay state. These fields are never used as client prediction inputs.
	private final RingVehicleInventory inventory = new RingVehicleInventory(this);
	private final Map<Long, Float> miningProgress = new HashMap<>();
	private final Map<Integer, Integer> impactAttackCooldowns = new HashMap<>();
	private float accumulatedDamage;
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
	private int discEmergencyLiftTicks;
	private double discEmergencyLiftSpeed;
	private boolean discSmashActive;
	private boolean discSmashFullCharge;
	private int discSmashTicks;
	private double discSmashSpeed;
	private double clientTargetX;
	private double clientTargetY;
	private double clientTargetZ;
	private float clientTargetYaw;
	private float clientTargetPitch;
	private int clientLerpTicks;
	private Vec3d lastVisualPosition = Vec3d.ZERO;

	public RingVehicleEntity(EntityType<? extends RingVehicleEntity> type, World world) {
		super(type, world);
		setNoGravity(true);
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
		builder.add(DISC_EXTRA_MINECARTS, 0);
		builder.add(FLIGHT_ROTOR_SPEED, 0.0F);
		builder.add(DISC_FLIGHT_ACTIVE, false);
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
		boolean producingLift = activelySpinning && angularRatio > 0.0D
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
		boolean floatingOnFluid = nearSupportedFluid && !producingLift && !discSmashActive;
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

		if (activelySpinning && age % 4 == 0 && getWorld() instanceof ServerWorld world) {
			if (magnitude >= DISC_LIFT_THRESHOLD) {
				world.spawnParticles(ParticleTypes.CLOUD, getX(), getY() - 0.05D, getZ(),
						2 + getDiscExtraMinecarts() / 2,
						getRingDiameter() * 0.22D, 0.03D, getRingDiameter() * 0.22D, 0.015D);
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
			float transferEfficiency = 0.92F + strength * 0.06F;
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

	private double maximumClutchAngularSpeed() {
		double ringScale = isExpandedRing() ? 1.2D : 1.0D;
		if (!getVariant().powered()) {
			return 72.0D * ringScale;
		}
		return (isHighGear() ? 144.0D : 96.0D) * ringScale;
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
		if (jumpQueued) {
			jumpQueued = false;
			ItemStack feet = inventory.getStack(RingVehicleInventory.ABILITY_JUMP);
			if (!abilityAirborne && feet.isOf(Items.RABBIT_FOOT) && getContactNormal().y > 0.8D) {
				double blocks = 1.0D + Math.max(0, feet.getCount() - 1);
				double charge = 0.15D + pendingJumpCharge * 0.85D;
				double jumpVelocity = Math.sqrt(0.16D * Math.max(0.25D, blocks * charge));
				Vec3d horizontal = getVelocity().multiply(1.0D, 0.0D, 1.0D);
				setVelocity(horizontal.add(0.0D, Math.min(3.2D, jumpVelocity), 0.0D));
				abilityAirborne = true;
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
		boolean landed = groundCollision || (verticalCollision && velocity.y < 0.0D)
				|| getPos().squaredDistanceTo(before) < 1.0E-5D && velocity.y < -0.08D;
		if (landed) {
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
			if (!getWorld().isClient()) {
				player.startRiding(this);
			}
			return ActionResult.success(getWorld().isClient());
		}
		return ActionResult.PASS;
	}

	@Override
	public ActionResult interactAt(PlayerEntity player, Vec3d hitPos, Hand hand) {
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
		return getPassengerList().isEmpty();
	}

	@Override
	public Vec3d getPassengerRidingPos(Entity passenger) {
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
				&& player.getMainHandStack().isOf(Items.MACE)) {
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
		accumulatedDamage += amount;
		if (accumulatedDamage >= 8.0F) {
			destroyAndDropContents(true);
		}
		return true;
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

	private void setDiscMode(boolean discMode) {
		dataTracker.set(DISC_MODE, discMode);
		if (!discMode) {
			setFlightRotorSpeed(0.0F);
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
		return isDiscMode() ? DISC_RENDER_CENTER_HEIGHT : getRingDiameter() * 0.5D;
	}

	public float getDiscVisualBlend(float tickDelta) {
		return MathHelper.lerp(tickDelta, previousDiscVisualBlend, discVisualBlend);
	}

	public double getVisualRenderCenterHeight(float tickDelta) {
		return MathHelper.lerp(getDiscVisualBlend(tickDelta), getRingDiameter() * 0.5D, DISC_RENDER_CENTER_HEIGHT);
	}

	public Vec3d getDiscVisualShakeOffset(float tickDelta) {
		if (!isDiscMode()) {
			return Vec3d.ZERO;
		}
		double time = age + tickDelta;
		Vec3d offset = Vec3d.ZERO;
		if (getClutchPhase() == CLUTCH_SPINNING && getInnerCartAngularSpeed() > 0.0F) {
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
		return getWaterVisualBob(tickDelta) + getUphillVisualBob(tickDelta);
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
		previousVisualRingAngle = visualRingAngle;
		previousVisualInnerCartAngle = visualInnerCartAngle;
		previousDiscVisualBlend = discVisualBlend;
		visualInnerCartAngle = getTrackedInnerCartAngle();
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
		setBoundingBox(isDiscMode()
				? discCollisionBounds(getPos(), getRingLevel())
				: collisionBounds(getPos(), Vec3d.of(Direction.UP.getVector()), getBodyHeading(), getRingLevel()));
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
		nbt.putBoolean("DiscMode", isDiscMode());
		nbt.putInt("DiscExtraMinecarts", getDiscExtraMinecarts());
		nbt.putFloat("FlightRotorSpeed", getFlightRotorSpeed());
		nbt.putBoolean("DiscFlightActive", isDiscFlightActive());
		nbt.putInt("InventoryVersion", RingVehicleInventory.DATA_VERSION);
		Inventories.writeNbt(nbt, inventory.stacks(), getRegistryManager());
	}

	public void readItemData(NbtCompound nbt) {
		setRingLevel(nbt.getInt("RingLevel"));
		setDiscMode(nbt.getBoolean("DiscMode"));
		setDiscExtraMinecarts(nbt.getInt("DiscExtraMinecarts"));
		setFlightRotorSpeed(nbt.getFloat("FlightRotorSpeed"));
		setDiscFlightActive(nbt.getBoolean("DiscFlightActive"));
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
		if (nbt.contains("ContactNormal")) {
			setContactNormal(vectorFromNbt(nbt.getCompound("ContactNormal")));
		}
		if (nbt.contains("Forward")) {
			setForwardVector(vectorFromNbt(nbt.getCompound("Forward")));
		}
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound nbt) {
		nbt.putInt("Variant", getVariant().ordinal());
		nbt.putBoolean("LavaProof", isLavaProof());
		writeItemData(nbt);
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
