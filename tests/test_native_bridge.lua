local A = require("tests.support.assertions")
local Config = require("ApolloMPSync/Config")
local FakePZ = require("tests.support.fake_pz")
local NativeAssistServer = require("ApolloMPSync/NativeAssistServer")
local Protocol = require("ApolloMPSync/Protocol")
local ServerRuntime = require("ApolloMPSync/ServerRuntime")

-- The production bridge exports exact Kahlua functions. Compatibility userdata/tables with
-- an `invoke` property must not be indexed because exact Kahlua reports a non-table error.
local decoratedApi = {}
NativeAssistServer.decorateApi(decoratedApi)
local directBridge = {
    status = function()
        return {
            state = "ABSENT",
            reasonCode = "direct-kahlua-callable",
            bridgeProtocol = "1",
            fingerprintSha256 = "fixture"
        }
    end,
    metrics = function() return { hit_gate_accepts = 3 } end
}
local directStatus = decoratedApi.nativeBridgeStatus(directBridge)
A.equal(type(directStatus), "table")
A.equal(directStatus.state, "ABSENT")
A.equal(directStatus.reasonCode, "direct-kahlua-callable")
local directMetrics = decoratedApi.nativeBridgeMetrics(directBridge)
A.equal(type(directMetrics), "table")
A.equal(tonumber(directMetrics.hit_gate_accepts), 3)

-- Break caught: requiring luajava/Java record construction for the exact Kahlua
-- handshake instead of passing the closed 25-field plain Lua table.
local canonicalHandshakeKeys = {
    workshopId = true, luaModId = true, bridgeProtocol = true,
    nativeAssistEnabled = true, serverRewindEnabled = true,
    pvpRewindEnabled = true, pveRewindEnabled = true,
    playerNativeAssistEnabled = true, zombieCombatBubbleEnabled = true,
    vehicleNativeAssistEnabled = true, directPlayerCorrection = true,
    directVehicleCorrection = true, historyMs = true, combatSampleMs = true,
    maxRewindMs = true, hardMaxRewindMs = true, rttCutoffMs = true,
    jitterCutoffMs = true, rangeEpsilonTiles = true,
    divergenceRejectTiles = true, combatBubbleInnerRadius = true,
    combatBubbleOuterRadius = true, combatBubbleMaxPlayers = true,
    combatBubbleMaxZombies = true, diagnosticsIntervalSeconds = true
}
local handshakeValues = {
    workshopId = "3780069702", luaModId = "ApolloMPSyncB42",
    bridgeProtocol = "1", nativeAssistEnabled = true,
    serverRewindEnabled = true, pvpRewindEnabled = true,
    pveRewindEnabled = true, playerNativeAssistEnabled = true,
    zombieCombatBubbleEnabled = true, vehicleNativeAssistEnabled = true,
    directPlayerCorrection = false, directVehicleCorrection = false,
    historyMs = 750, combatSampleMs = 100, maxRewindMs = 150,
    hardMaxRewindMs = 200, rttCutoffMs = 300, jitterCutoffMs = 50,
    rangeEpsilonTiles = 0.25, divergenceRejectTiles = 2.5,
    combatBubbleInnerRadius = 8, combatBubbleOuterRadius = 15,
    combatBubbleMaxPlayers = 12, combatBubbleMaxZombies = 48,
    diagnosticsIntervalSeconds = 60, hostileExtra = "must-not-cross"
}
local previousLuaJava = rawget(_G, "luajava")
rawset(_G, "luajava", nil)
local receivedHandshake = nil
directBridge.handshake = function(values)
    receivedHandshake = values
    return directBridge.status()
end
local directHandshakeStatus = decoratedApi.nativeBridgeHandshake(directBridge, handshakeValues)
rawset(_G, "luajava", previousLuaJava)
A.equal(directHandshakeStatus.reasonCode, "direct-kahlua-callable")
A.equal(type(receivedHandshake), "table")
local handshakeKeyCount = 0
for key, value in pairs(receivedHandshake) do
    handshakeKeyCount = handshakeKeyCount + 1
    A.equal(canonicalHandshakeKeys[key], true)
    A.equal(value, handshakeValues[key])
end
A.equal(handshakeKeyCount, 25)
A.equal(receivedHandshake.hostileExtra, nil)
local legacyOk = pcall(decoratedApi.nativeBridgeStatus, {
    status = {
        invoke = function()
            return { state = "READY", reasonCode = "legacy-invoke-must-not-run" }
        end
    }
})
A.equal(legacyOk, false)
local rawStatusOk = pcall(decoratedApi.nativeBridgeStatus, {
    status = function() return "java-userdata-placeholder" end
})
A.equal(rawStatusOk, false)
local rawMetricsOk = pcall(decoratedApi.nativeBridgeMetrics, {
    metrics = function() return "java-map-userdata-placeholder" end
})
A.equal(rawMetricsOk, false)

local function semanticAction(api, state)
    local bridge = api.installNativeBridge({ state = state, reasonCode = "test-" .. string.lower(state) })
    local sender = api.addPlayer({ id = "sender", steamId = "steam-sender", onlineId = 10 })
    api.addPlayer({ id = "observer", steamId = "steam-observer", onlineId = 11, x = 1 })
    local runtime = ServerRuntime.new(api, Config.defaults())
    runtime:onClientCommand(Protocol.NAMESPACE, Protocol.CHANNELS.action, sender, {
        protocolVersion = Protocol.VERSION,
        epoch = "session-1",
        sequence = 1,
        key = "read",
        phase = "start",
        progress = 0,
        anchor = { x = 0, y = 0, z = 0, facing = 0 },
        animationVars = { PerformingAction = true, IsReading = true }
    })
    A.equal(#api.serverCommands, 1)
    A.equal(api.serverCommands[1].command, Protocol.CHANNELS.action)
    return runtime, bridge
end

-- Break caught: enabling native decisions for a bridge state other than an exact accepted READY handshake.
for _, state in ipairs({ "READY", "ABSENT", "DISABLED", "INCOMPATIBLE", "CIRCUIT_OPEN" }) do
    local api = FakePZ.new()
    local runtime, bridge = semanticAction(api, state)
    A.equal(runtime.nativeAssist:allowsDecisions(), state == "READY")
    runtime.nativeAssist:refresh()
    runtime.nativeAssist:refresh()
    A.equal(#api.nativeStateLogs, 1)
    A.equal(api.nativeStateLogs[1].state, state)
    local expectedHandshakes = (state == "READY" or state == "ABSENT") and 1 or 0
    A.equal(bridge.handshakeCalls, expectedHandshakes)
end

-- Break caught: arming READY with a different Workshop/Lua/bridge tuple or unsafe config.
local mismatchApi = FakePZ.new()
local mismatchBridge = mismatchApi.installNativeBridge({
    state = "READY",
    reasonCode = "exact-match",
    handshakeState = "INCOMPATIBLE",
    handshakeReasonCode = "bridge-handshake-mismatch"
})
local mismatch = NativeAssistServer.new(mismatchApi, Config.defaults()):initialize()
A.equal(mismatch:allowsDecisions(), false)
A.equal(mismatch.state, "INCOMPATIBLE")
A.equal(mismatch.reasonCode, "bridge-handshake-mismatch")
A.equal(mismatchBridge.handshakes[1].workshopId, "3780069702")
A.equal(mismatchBridge.handshakes[1].luaModId, "ApolloMPSyncB42")
A.equal(mismatchBridge.handshakes[1].bridgeProtocol, "1")
A.equal(mismatchBridge.handshakes[1].nativeAssistEnabled, true)
A.equal(mismatchBridge.handshakes[1].serverRewindEnabled, true)
A.equal(mismatchBridge.handshakes[1].pvpRewindEnabled, true)
A.equal(mismatchBridge.handshakes[1].pveRewindEnabled, true)
A.equal(mismatchBridge.handshakes[1].playerNativeAssistEnabled, true)
A.equal(mismatchBridge.handshakes[1].zombieCombatBubbleEnabled, true)
A.equal(mismatchBridge.handshakes[1].vehicleNativeAssistEnabled, true)
A.equal(mismatchBridge.handshakes[1].directPlayerCorrection, false)
A.equal(mismatchBridge.handshakes[1].directVehicleCorrection, false)
A.equal(mismatchBridge.handshakes[1].historyMs, 750)
A.equal(mismatchBridge.handshakes[1].combatSampleMs, 100)
A.equal(mismatchBridge.handshakes[1].maxRewindMs, 150)
A.equal(mismatchBridge.handshakes[1].hardMaxRewindMs, 200)
A.equal(mismatchBridge.handshakes[1].rttCutoffMs, 300)
A.equal(mismatchBridge.handshakes[1].jitterCutoffMs, 50)
A.equal(mismatchBridge.handshakes[1].rangeEpsilonTiles, 0.25)
A.equal(mismatchBridge.handshakes[1].divergenceRejectTiles, 2.5)
A.equal(mismatchBridge.handshakes[1].combatBubbleInnerRadius, 8)
A.equal(mismatchBridge.handshakes[1].combatBubbleOuterRadius, 15)
A.equal(mismatchBridge.handshakes[1].combatBubbleMaxPlayers, 12)
A.equal(mismatchBridge.handshakes[1].combatBubbleMaxZombies, 48)
A.equal(mismatchBridge.handshakes[1].diagnosticsIntervalSeconds, 60)

local unsafe = Config.defaults()
unsafe.historyMs = 751
local unsafeApi = FakePZ.new()
local unsafeBridge = unsafeApi.installNativeBridge({ state = "READY" })
local rejectedConfig = NativeAssistServer.new(unsafeApi, unsafe):initialize()
A.equal(rejectedConfig.state, "INCOMPATIBLE")
A.equal(rejectedConfig.reasonCode, "native-config-mismatch")
A.equal(rejectedConfig:allowsDecisions(), false)
A.equal(unsafeBridge.handshakeCalls, 0)

local excessiveRewind = Config.defaults()
excessiveRewind.maxRewindMs = 151
local excessiveApi = FakePZ.new()
local excessiveBridge = excessiveApi.installNativeBridge({ state = "READY" })
local rejectedRewind = NativeAssistServer.new(excessiveApi, excessiveRewind):initialize()
A.equal(rejectedRewind.state, "INCOMPATIBLE")
A.equal(rejectedRewind.reasonCode, "native-rewind-cap")
A.equal(rejectedRewind:allowsDecisions(), false)
A.equal(excessiveBridge.handshakeCalls, 0)

-- Break caught: accepting a live immutable config mutation after startup validation.
local immutableMutations = {
    { "historyMs", 751 },
    { "combatSampleMs", 101 },
    { "hardMaxRewindMs", 201 },
    { "rttCutoffMs", 301 },
    { "jitterCutoffMs", 51 },
    { "rangeEpsilonTiles", 0.26 },
    { "divergenceRejectTiles", 2.6 },
    { "combatBubbleInnerRadius", 9 },
    { "combatBubbleOuterRadius", 16 },
    { "combatBubbleMaxPlayers", 13 },
    { "combatBubbleMaxZombies", 49 },
    { "diagnosticsIntervalSeconds", 61 }
}
for _, mutation in ipairs(immutableMutations) do
    local config = Config.defaults()
    local api = FakePZ.new()
    local bridge = api.installNativeBridge({ state = "READY", reasonCode = "exact-match" })
    local assist = NativeAssistServer.new(api, config):initialize()
    A.equal(assist:allowsDecisions(), true)
    config[mutation[1]] = mutation[2]
    assist:refresh()
    A.equal(assist.state, "INCOMPATIBLE")
    A.equal(assist.reasonCode, "native-config-mismatch")
    A.equal(assist:allowsDecisions(), false)
    assist:refresh()
    A.equal(assist.state, "INCOMPATIBLE")
    A.equal(assist:allowsDecisions(), false)
    A.equal(bridge.handshakeCalls, 2)
    A.equal(bridge.handshakes[2].nativeAssistEnabled, false)
end

local function readyAssist()
    local config = Config.defaults()
    local api = FakePZ.new()
    local bridge = api.installNativeBridge({ state = "READY", reasonCode = "hooks-armed" })
    local assist = NativeAssistServer.new(api, config):initialize()
    A.equal(assist:allowsDecisions(), true)
    A.equal(bridge.handshakeCalls, 1)
    return assist, config, bridge
end

-- Break caught: restoring a decision-only invalid mutation without a refresh resurrecting
-- historical authorization in Lua or leaving the Java bridge authorized.
local decisionInvalidated, decisionConfig, decisionBridge = readyAssist()
decisionConfig.historyMs = 751
A.equal(decisionInvalidated:allowsDecisions(), false)
A.equal(decisionBridge.handshakeCalls, 2)
local deauthorization = decisionBridge.handshakes[2]
A.equal(deauthorization.nativeAssistEnabled, false)
A.equal(deauthorization.serverRewindEnabled, false)
A.equal(deauthorization.pvpRewindEnabled, false)
A.equal(deauthorization.pveRewindEnabled, false)
A.equal(deauthorization.playerNativeAssistEnabled, false)
A.equal(deauthorization.zombieCombatBubbleEnabled, false)
A.equal(deauthorization.vehicleNativeAssistEnabled, false)
A.equal(deauthorization.directPlayerCorrection, false)
A.equal(deauthorization.directVehicleCorrection, false)
A.equal(deauthorization.historyMs, 750)
A.equal(deauthorization.maxRewindMs, 150)
A.equal(deauthorization.diagnosticsIntervalSeconds, 60)
decisionConfig.historyMs = 750
A.equal(decisionInvalidated:allowsDecisions(), false)
A.equal(decisionBridge.handshakeCalls, 2)
decisionInvalidated:refresh()
A.equal(decisionBridge.handshakeCalls, 3)
A.equal(decisionBridge.handshakes[3].nativeAssistEnabled, true)
A.equal(decisionInvalidated:allowsDecisions(), true)

-- Break caught: reusing historical acceptance after READY leaves and later returns.
for _, terminalState in ipairs({ "CIRCUIT_OPEN", "DISABLED" }) do
    local assist, _, bridge = readyAssist()
    bridge.state = terminalState
    bridge.reasonCode = "test-" .. string.lower(terminalState)
    assist:refresh()
    A.equal(assist:allowsDecisions(), false)
    bridge.state = "READY"
    bridge.reasonCode = "hooks-armed"
    assist:refresh()
    A.equal(bridge.handshakeCalls, 3)
    A.equal(bridge.handshakes[2].nativeAssistEnabled, false)
    A.equal(bridge.handshakes[3].nativeAssistEnabled, true)
    A.equal(assist:allowsDecisions(), true)
end

-- Break caught: accepting a restored protocol/reason without a fresh exact handshake.
local drifted, _, driftBridge = readyAssist()
driftBridge.bridgeProtocol = "2"
driftBridge.reasonCode = "protocol-drift"
drifted:refresh()
A.equal(drifted:allowsDecisions(), false)
driftBridge.state = "READY"
driftBridge.bridgeProtocol = "1"
driftBridge.reasonCode = "hooks-armed"
drifted:refresh()
A.equal(driftBridge.handshakeCalls, 3)
A.equal(driftBridge.handshakes[2].nativeAssistEnabled, false)
A.equal(driftBridge.handshakes[3].nativeAssistEnabled, true)
A.equal(drifted:allowsDecisions(), true)
driftBridge.reasonCode = "status-reason-changed"
drifted:refresh()
A.equal(driftBridge.handshakeCalls, 5)
A.equal(drifted:allowsDecisions(), true)

-- Break caught: changing a valid live feature flag without renegotiating its exact snapshot.
local reconfigured, liveConfig, configBridge = readyAssist()
liveConfig.pvpRewind = false
reconfigured:refresh()
A.equal(configBridge.handshakeCalls, 3)
A.equal(configBridge.handshakes[2].nativeAssistEnabled, false)
A.equal(configBridge.handshakes[3].pvpRewindEnabled, false)
A.equal(reconfigured:allowsDecisions(), true)
liveConfig.nativeAssistEnabled = false
reconfigured:refresh()
A.equal(reconfigured.state, "DISABLED")
A.equal(reconfigured:allowsDecisions(), false)
liveConfig.nativeAssistEnabled = true
reconfigured:refresh()
A.equal(configBridge.handshakeCalls, 5)
A.equal(reconfigured:allowsDecisions(), true)

-- Break caught: polling native metrics per packet or omitting required aggregate diagnostic groups.
local diagnosticsApi = FakePZ.new()
local diagnosticsBridge = diagnosticsApi.installNativeBridge({
    state = "READY",
    metrics = {
        hit_gate_accepts = 9,
        hit_gate_rejections = 3,
        decision_accept_rewind = 5,
        decision_reject_range = 2,
        history_misses = 4,
        rewind_fallback_rtt = 6,
        rewind_fallback_jitter = 7,
        bubble_active = 1,
        bubble_players = 2,
        bubble_zombies = 8,
        zombie_handoffs = 10,
        circuit_open = 0
    }
})
local diagnosticsRuntime = ServerRuntime.new(diagnosticsApi, Config.defaults())
diagnosticsRuntime.config.diagnosticsIntervalSeconds = 10
diagnosticsApi.clock.nowMs = 11000
diagnosticsRuntime:onTick()
A.equal(diagnosticsBridge.metricsCalls, 0)
A.equal(#diagnosticsApi.diagnostics, 0)
diagnosticsRuntime.config.diagnosticsIntervalSeconds = 60
diagnosticsApi.clock.nowMs = 60999
diagnosticsRuntime:onTick()
A.equal(diagnosticsBridge.metricsCalls, 0)
A.equal(#diagnosticsApi.diagnostics, 0)
diagnosticsApi.clock.nowMs = 61000
diagnosticsRuntime:onTick()
A.equal(diagnosticsBridge.metricsCalls, 1)
A.equal(#diagnosticsApi.diagnostics, 1)
local native = diagnosticsApi.diagnostics[1].nativeAssist
A.equal(native.state, "READY")
A.equal(native.accepted.total, 9)
A.equal(native.accepted.rewind, 5)
A.equal(native.rejected.total, 3)
A.equal(native.rejected.range, 2)
A.equal(native.historyMisses, 4)
A.equal(native.rttFallbacks, 6)
A.equal(native.jitterFallbacks, 7)
A.equal(native.bubblePopulation.active, 1)
A.equal(native.bubblePopulation.players, 2)
A.equal(native.bubblePopulation.zombies, 8)
A.equal(native.handoffs, 10)
A.equal(native.circuitState, 0)
local formatted = ServerRuntime.formatDiagnostics(diagnosticsApi.diagnostics[1])
A.equal(string.find(formatted, "nativeAccepted=current:0,rewind:5,total:9", 1, true) ~= nil, true)
A.equal(string.find(formatted, "historyMisses=4 rttFallbacks=6 jitterFallbacks=7", 1, true) ~= nil, true)
A.equal(string.find(formatted, "bubble=active:1,players:2,zombies:8 handoffs=10 circuit=0", 1, true) ~= nil, true)

-- Break caught: clock rollback causing an early or duplicate native aggregate flush.
diagnosticsApi.clock.nowMs = 500
diagnosticsRuntime:onTick()
diagnosticsApi.clock.nowMs = 61000
diagnosticsRuntime:onTick()
diagnosticsApi.clock.nowMs = 120999
diagnosticsRuntime:onTick()
A.equal(diagnosticsBridge.metricsCalls, 1)
A.equal(#diagnosticsApi.diagnostics, 1)
diagnosticsApi.clock.nowMs = 121000
diagnosticsRuntime:onTick()
A.equal(diagnosticsBridge.metricsCalls, 2)
A.equal(#diagnosticsApi.diagnostics, 2)

print("PASS native bridge")
