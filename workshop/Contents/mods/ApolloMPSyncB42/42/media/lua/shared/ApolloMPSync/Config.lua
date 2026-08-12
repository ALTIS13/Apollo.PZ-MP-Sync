local Config = {}
local ActionRegistry = require("ApolloMPSync/ActionRegistry")

local DEFAULTS = {
    enabled = true,
    playerSync = true,
    actionSync = true,
    sleepSync = true,
    actionCategorySync = true,
    actionProgressIntervalMs = 500,
    postActionGraceMs = 1250,
    playerRadius = 60,
    movingIntervalMs = 200,
    idleIntervalMs = 1000,
    softThresholdTiles = 2.5,
    softConfirmations = 4,
    maxSoftStepTiles = 0.25,
    hardSnap = false,
    vehicleTransformSync = true,
    vehicleVisualPartSync = true,
    vehicleRadius = 300,
    vehicleIntervalMinMs = 100,
    vehicleIntervalMaxMs = 250,
    vehicleRepairIntervalSeconds = 5,
    trailerSync = true,
    authoritativeAssist = false,
    directPlayerCorrection = false,
    directVehicleCorrection = false,
    debugLogging = false,
    diagnosticsIntervalSeconds = 60,
    nativeAssistEnabled = true,
    serverRewindEnabled = true,
    pvpRewind = true,
    pveRewind = true,
    playerNativeAssist = true,
    zombieCombatBubble = true,
    vehicleNativeAssist = true,
    historyMs = 750,
    combatSampleMs = 100,
    maxRewindMs = 150,
    hardMaxRewindMs = 200,
    rttCutoffMs = 300,
    jitterCutoffMs = 50,
    rangeEpsilonTiles = 0.25,
    divergenceRejectTiles = 2.5,
    combatBubbleInnerRadius = 8,
    combatBubbleOuterRadius = 15,
    combatBubbleMaxPlayers = 12,
    combatBubbleMaxZombies = 48,
}

local function copyDefaults()
    local copy = {}
    for key, value in pairs(DEFAULTS) do
        copy[key] = value
    end
    return copy
end

local function canonicalize(config)
    config.DirectPlayerCorrection = false
    config.DirectVehicleCorrection = false
    config.directPlayerCorrection = false
    config.directVehicleCorrection = false
    config.actionEnabled = config.actionSync
    config.sleepEnabled = config.sleepSync
    config.progressIntervalMs = config.actionProgressIntervalMs
    config.endGraceMs = config.postActionGraceMs
    config.softThreshold = config.softThresholdTiles
    config.confirmations = config.softConfirmations
    config.maxStep = config.maxSoftStepTiles
    config.highwayRadius = config.vehicleRadius

    config.categoryEnabled = {}
    for _, category in ipairs(ActionRegistry.categories()) do
        config.categoryEnabled[category] = config.actionCategorySync
    end

    return config
end

local function clampedNumber(value, fallback, minimum, maximum)
    if type(value) ~= "number" or value ~= value then
        return fallback
    end
    if value < minimum then
        return minimum
    end
    if value > maximum then
        return maximum
    end
    return value
end

local function boolean(value, fallback)
    if type(value) == "boolean" then
        return value
    end
    return fallback
end

local function optionsFor(sandbox)
    if type(sandbox) ~= "table" then
        return {}
    end
    if type(sandbox.ApolloMPSync) == "table" then
        return sandbox.ApolloMPSync
    end
    return sandbox
end

function Config.defaults()
    return canonicalize(copyDefaults())
end

function Config.fromSandbox(sandbox)
    local options = optionsFor(sandbox)
    local config = copyDefaults()

    config.enabled = boolean(options.Enabled, config.enabled)
    config.playerSync = boolean(options.PlayerSync, config.playerSync)
    config.actionSync = boolean(options.ActionSync, config.actionSync)
    config.sleepSync = boolean(options.SleepSync, config.sleepSync)
    config.actionCategorySync = boolean(options.ActionCategorySync, config.actionCategorySync)
    config.actionProgressIntervalMs = clampedNumber(options.ActionProgressIntervalMs,
        config.actionProgressIntervalMs, 50, 5000)
    config.postActionGraceMs = clampedNumber(options.PostActionGraceMs,
        config.postActionGraceMs, 50, 5000)
    config.playerRadius = clampedNumber(options.PlayerRadius, config.playerRadius, 10, 500)
    config.movingIntervalMs = clampedNumber(options.MovingIntervalMs, config.movingIntervalMs, 50, 5000)
    config.idleIntervalMs = clampedNumber(options.IdleIntervalMs, config.idleIntervalMs, 50, 5000)
    config.softThresholdTiles = clampedNumber(options.SoftThresholdTiles,
        config.softThresholdTiles, 0.5, 20)
    config.softConfirmations = clampedNumber(options.SoftConfirmations,
        config.softConfirmations, 1, 10)
    config.maxSoftStepTiles = clampedNumber(options.MaxSoftStepTiles,
        config.maxSoftStepTiles, 0.05, 2)
    -- Hard snap is intentionally unavailable as an active behavior in release 0.2.0.
    config.hardSnap = false
    config.vehicleTransformSync = boolean(options.VehicleTransformSync, config.vehicleTransformSync)
    config.vehicleVisualPartSync = boolean(options.VehicleVisualPartSync, config.vehicleVisualPartSync)
    config.vehicleRadius = clampedNumber(options.VehicleRadius, config.vehicleRadius, 10, 500)
    config.vehicleIntervalMinMs = clampedNumber(options.VehicleIntervalMinMs,
        config.vehicleIntervalMinMs, 50, 5000)
    config.vehicleIntervalMaxMs = clampedNumber(options.VehicleIntervalMaxMs,
        config.vehicleIntervalMaxMs, 50, 5000)
    if config.vehicleIntervalMinMs > config.vehicleIntervalMaxMs then
        config.vehicleIntervalMinMs, config.vehicleIntervalMaxMs =
            config.vehicleIntervalMaxMs, config.vehicleIntervalMinMs
    end
    config.vehicleRepairIntervalSeconds = clampedNumber(options.VehicleRepairIntervalSeconds,
        config.vehicleRepairIntervalSeconds, 1, 60)
    config.trailerSync = boolean(options.TrailerSync, config.trailerSync)
    -- Movement authority is native-only. Sandbox values must never opt Lua into correction.
    config.authoritativeAssist = false
    config.directPlayerCorrection = false
    config.directVehicleCorrection = false
    config.debugLogging = boolean(options.DebugLogging, config.debugLogging)
    config.diagnosticsIntervalSeconds = 60
    config.nativeAssistEnabled = boolean(options.NativeAssistEnabled, config.nativeAssistEnabled)
    config.serverRewindEnabled = boolean(options.ServerRewindEnabled, config.serverRewindEnabled)
    config.pvpRewind = boolean(options.PvPRewind, config.pvpRewind)
    config.pveRewind = boolean(options.PvERewind, config.pveRewind)
    config.playerNativeAssist = boolean(options.PlayerNativeAssist, config.playerNativeAssist)
    config.zombieCombatBubble = boolean(options.ZombieCombatBubble, config.zombieCombatBubble)
    config.vehicleNativeAssist = boolean(options.VehicleNativeAssist, config.vehicleNativeAssist)
    config.maxRewindMs = clampedNumber(options.MaxRewindMs, config.maxRewindMs, 0, 150)

    return canonicalize(config)
end

return Config
