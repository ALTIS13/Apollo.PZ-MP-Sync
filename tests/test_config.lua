local A = require("tests.support.assertions")
local Config = require("ApolloMPSync/Config")
local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local ActionPolicy = require("ApolloMPSync/ActionPolicy")
local Reconciliation = require("ApolloMPSync/Reconciliation")
local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")

local defaults = Config.defaults()

-- Break caught: changing the approved conservative settings that protect standard multiplayer mode.
A.equal(defaults.enabled, true)
A.equal(defaults.playerSync, true)
A.equal(defaults.actionSync, true)
A.equal(defaults.sleepSync, true)
A.equal(defaults.actionCategorySync, true)
A.equal(defaults.actionProgressIntervalMs, 500)
A.equal(defaults.postActionGraceMs, 1250)
A.equal(defaults.playerRadius, 60)
A.equal(defaults.movingIntervalMs, 200)
A.equal(defaults.idleIntervalMs, 1000)
A.equal(defaults.softThresholdTiles, 2.5)
A.equal(defaults.softConfirmations, 4)
A.equal(defaults.maxSoftStepTiles, 0.25)
A.equal(defaults.hardSnap, false)
A.equal(defaults.vehicleTransformSync, true)
A.equal(defaults.vehicleVisualPartSync, true)
A.equal(defaults.vehicleRadius, 300)
A.equal(defaults.vehicleIntervalMinMs, 100)
A.equal(defaults.vehicleIntervalMaxMs, 250)
A.equal(defaults.vehicleRepairIntervalSeconds, 5)
A.equal(defaults.trailerSync, true)
A.equal(defaults.authoritativeAssist, false)
A.equal(defaults.directPlayerCorrection, false)
A.equal(defaults.directVehicleCorrection, false)
A.equal(defaults.DirectPlayerCorrection, false)
A.equal(defaults.DirectVehicleCorrection, false)
A.equal(defaults.debugLogging, false)
A.equal(defaults.diagnosticsIntervalSeconds, 60)
A.equal(defaults.nativeAssistEnabled, true)
A.equal(defaults.serverRewindEnabled, true)
A.equal(defaults.pvpRewind, true)
A.equal(defaults.pveRewind, true)
A.equal(defaults.playerNativeAssist, true)
A.equal(defaults.zombieCombatBubble, true)
A.equal(defaults.vehicleNativeAssist, true)
A.equal(defaults.historyMs, 750)
A.equal(defaults.combatSampleMs, 100)
A.equal(defaults.maxRewindMs, 150)
A.equal(defaults.hardMaxRewindMs, 200)
A.equal(defaults.rttCutoffMs, 300)
A.equal(defaults.jitterCutoffMs, 50)
A.equal(defaults.rangeEpsilonTiles, 0.25)
A.equal(defaults.divergenceRejectTiles, 2.5)
A.equal(defaults.combatBubbleInnerRadius, 8)
A.equal(defaults.combatBubbleOuterRadius, 15)
A.equal(defaults.combatBubbleMaxPlayers, 12)
A.equal(defaults.combatBubbleMaxZombies, 48)
A.equal(defaults.actionEnabled, true)
A.equal(defaults.sleepEnabled, true)
A.equal(defaults.progressIntervalMs, 500)
A.equal(defaults.endGraceMs, 1250)
A.equal(defaults.softThreshold, 2.5)
A.equal(defaults.confirmations, 4)
A.equal(defaults.maxStep, 0.25)
A.equal(defaults.highwayRadius, 300)
for _, category in ipairs(ActionRegistry.categories()) do
    A.equal(defaults.categoryEnabled[category], true)
end

-- Break caught: allowing hostile or malformed SandboxVars to make policies operate outside their safe ranges.
local normalized = Config.fromSandbox({
    ApolloMPSync = {
        PlayerRadius = -10,
        MovingIntervalMs = -1,
        IdleIntervalMs = 999999,
        SoftThresholdTiles = -1,
        SoftConfirmations = 99,
        MaxSoftStepTiles = -1,
        ActionProgressIntervalMs = 0 / 0,
        PostActionGraceMs = -1,
        VehicleRadius = math.huge,
        VehicleIntervalMinMs = 900,
        VehicleIntervalMaxMs = 100,
        VehicleRepairIntervalSeconds = 0,
        DiagnosticsIntervalSeconds = 999999,
        HardSnap = true,
        MaxRewindMs = 999,
    }
})

A.equal(normalized.playerRadius, 10)
A.equal(normalized.movingIntervalMs, 50)
A.equal(normalized.idleIntervalMs, 5000)
A.equal(normalized.softThresholdTiles, 0.5)
A.equal(normalized.softConfirmations, 10)
A.equal(normalized.maxSoftStepTiles, 0.05)
A.equal(normalized.actionProgressIntervalMs, 500)
A.equal(normalized.postActionGraceMs, 50)
A.equal(normalized.vehicleRadius, 500)
A.equal(normalized.vehicleIntervalMinMs, 100)
A.equal(normalized.vehicleIntervalMaxMs, 900)
A.equal(normalized.vehicleRepairIntervalSeconds, 1)
A.equal(normalized.diagnosticsIntervalSeconds, 60)
for _, hostileInterval in ipairs({ 10, 59, 61, 600 }) do
    A.equal(Config.fromSandbox({ DiagnosticsIntervalSeconds = hostileInterval })
        .diagnosticsIntervalSeconds, 60)
end
A.equal(normalized.hardSnap, false)
A.equal(normalized.maxRewindMs, 150)

-- Break caught: exposing Java's defensive 200 ms hard cap as an administrator-authorizable rewind.
for _, hostileMax in ipairs({ 151, 200, 999 }) do
    A.equal(Config.fromSandbox({ MaxRewindMs = hostileMax }).maxRewindMs, 150)
end

-- Break caught: refusing valid server sandbox values or silently applying malformed boolean data.
local configured = Config.fromSandbox({
    Enabled = false,
    PlayerSync = false,
    ActionSync = false,
    SleepSync = false,
    ActionCategorySync = false,
    VehicleTransformSync = false,
    VehicleVisualPartSync = false,
    TrailerSync = false,
    AuthoritativeAssist = true,
    DebugLogging = true,
    NativeAssistEnabled = false,
    ServerRewindEnabled = false,
    PvPRewind = false,
    PvERewind = false,
    PlayerNativeAssist = false,
    ZombieCombatBubble = false,
    VehicleNativeAssist = false,
    DirectPlayerCorrection = true,
    DirectVehicleCorrection = true,
})
A.equal(configured.enabled, false)
A.equal(configured.playerSync, false)
A.equal(configured.actionSync, false)
A.equal(configured.sleepSync, false)
A.equal(configured.actionCategorySync, false)
A.equal(configured.vehicleTransformSync, false)
A.equal(configured.vehicleVisualPartSync, false)
A.equal(configured.trailerSync, false)
A.equal(configured.authoritativeAssist, false)
A.equal(configured.directPlayerCorrection, false)
A.equal(configured.directVehicleCorrection, false)
A.equal(configured.debugLogging, true)
A.equal(configured.nativeAssistEnabled, false)
A.equal(configured.serverRewindEnabled, false)
A.equal(configured.pvpRewind, false)
A.equal(configured.pveRewind, false)
A.equal(configured.playerNativeAssist, false)
A.equal(configured.zombieCombatBubble, false)
A.equal(configured.vehicleNativeAssist, false)
A.equal(configured.directPlayerCorrection, false)
A.equal(configured.directVehicleCorrection, false)

-- Break caught: handing policies descriptive Sandbox fields that they do not consume instead of canonical policy keys.
local policyConfig = Config.fromSandbox({
    ActionProgressIntervalMs = 1000,
    PostActionGraceMs = 2000,
    SoftThresholdTiles = 0.5,
    SoftConfirmations = 1,
    MaxSoftStepTiles = 0.5,
    VehicleRadius = 50,
})
A.equal(policyConfig.progressIntervalMs, 1000)
A.equal(policyConfig.endGraceMs, 2000)
A.equal(policyConfig.softThreshold, 0.5)
A.equal(policyConfig.confirmations, 1)
A.equal(policyConfig.maxStep, 0.5)
A.equal(policyConfig.highwayRadius, 50)

local function action(className, phase)
    return {
        className = className,
        phase = phase,
        anchor = { x = 10, y = 20, z = 0, facing = 90 },
        animationVars = { PerformingAction = true },
        progress = 0.5,
    }
end

local throttledState = {}
A.equal(ActionPolicy.accept(throttledState, action("ISReadABook", "start"), 0, policyConfig).phase, "start")
local throttled, throttledReason = ActionPolicy.accept(throttledState, action("ISReadABook", "progress"), 600, policyConfig)
A.equal(throttled, nil)
A.equal(throttledReason, "throttled")
local completed = ActionPolicy.accept(throttledState, action("ISReadABook", "complete"), 1000, policyConfig)
A.equal(completed.graceUntilMs, 3000)

local disabledActions = Config.fromSandbox({ ActionSync = false })
local actionDisabled, actionDisabledReason = ActionPolicy.accept({}, action("ISReadABook", "start"), 0, disabledActions)
A.equal(actionDisabled, nil)
A.equal(actionDisabledReason, "disabled")
local disabledSleep = Config.fromSandbox({ SleepSync = false })
local sleepDisabled, sleepDisabledReason = ActionPolicy.accept({}, action("ISSleepAction", "start"), 0, disabledSleep)
A.equal(sleepDisabled, nil)
A.equal(sleepDisabledReason, "disabled")
local disabledCategories = Config.fromSandbox({ ActionCategorySync = false })
-- Break caught: allowing a non-sleep built-in action to bypass the sandbox-derived category map.
local categoryState = {}
local categoryDisabled, categoryDisabledReason = ActionPolicy.accept(
    categoryState, action("ISReadABook", "start"), 0, disabledCategories)
A.equal(categoryDisabled, nil)
A.equal(categoryDisabledReason, "disabled")
A.equal(categoryState.activeKey, nil)
for _, category in ipairs(ActionRegistry.categories()) do
    A.equal(disabledCategories.categoryEnabled[category], false)
end

local observer = { id = "observer", x = 0, y = 0, z = 0 }
local report = { id = "remote", x = 3, y = 0, z = 0, cellLoaded = true }
local soft = Reconciliation.evaluate(Reconciliation.new(), observer, report, 0, policyConfig)
A.equal(soft.kind, "soft")
A.equal(soft.dx, 0.5)
local highThreshold = Config.fromSandbox({ SoftThresholdTiles = 5, SoftConfirmations = 1, MaxSoftStepTiles = 2 })
A.equal(Reconciliation.evaluate(Reconciliation.new(), observer, report, 0, highThreshold).kind, "none")

local function vehicleReport(sequence)
    return {
        vehicleId = "config-car",
        driverId = "driver",
        epoch = 1,
        sequence = sequence,
        transform = { x = 100, y = 0, z = 0, angle = 0, vx = 0, vy = 0, angularVelocity = 0, moving = false },
    }
end

local vehicleObserver = {
    x = 0, y = 0, z = 0, isLocalDriver = false,
    transform = { x = 0, y = 0, z = 0, angle = 0, vx = 0, vy = 0, angularVelocity = 0, moving = false },
}
local nearby = {}
A.equal(VehiclePolicy.acceptAuthority(nearby, "config-car", "driver", 1, 0), true)
A.equal(VehiclePolicy.reconcile(nearby, vehicleObserver, vehicleReport(1), 1, policyConfig).reason, "irrelevant")
local wideRadius = Config.fromSandbox({ VehicleRadius = 150 })
local relevant = {}
A.equal(VehiclePolicy.acceptAuthority(relevant, "config-car", "driver", 1, 0), true)
for sequence = 1, 2 do
    A.equal(VehiclePolicy.reconcile(relevant, vehicleObserver, vehicleReport(sequence), sequence, wideRadius).kind, "none")
end
A.equal(VehiclePolicy.reconcile(relevant, vehicleObserver, vehicleReport(3), 3, wideRadius).kind, "vanilla-reset")

print("PASS config")
