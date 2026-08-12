local Protocol = require("ApolloMPSync/Protocol")
local Validation = require("ApolloMPSync/Validation")

local NativeAssistServer = {}
NativeAssistServer.__index = NativeAssistServer

local STATES = {
    ABSENT = true,
    DISABLED = true,
    INCOMPATIBLE = true,
    READY = true,
    CIRCUIT_OPEN = true
}

local CONFIG_KEYS = {
    "nativeAssistEnabled", "serverRewindEnabled", "pvpRewind", "pveRewind",
    "playerNativeAssist", "zombieCombatBubble", "vehicleNativeAssist",
    "directPlayerCorrection", "directVehicleCorrection", "historyMs", "combatSampleMs",
    "maxRewindMs", "hardMaxRewindMs", "rttCutoffMs", "jitterCutoffMs",
    "rangeEpsilonTiles", "divergenceRejectTiles", "combatBubbleInnerRadius",
    "combatBubbleOuterRadius", "combatBubbleMaxPlayers", "combatBubbleMaxZombies",
    "diagnosticsIntervalSeconds"
}

local function configSignature(config)
    local values = { Protocol.WORKSHOP_ID, Protocol.MOD_ID, Protocol.BRIDGE_PROTOCOL }
    for _, key in ipairs(CONFIG_KEYS) do
        values[#values + 1] = key .. "=" .. tostring(type(config) == "table" and config[key] or nil)
    end
    return table.concat(values, "\31")
end

local function statusSignature(status)
    return table.concat({
        tostring(status.state or ""),
        tostring(status.reasonCode or ""),
        tostring(status.bridgeProtocol or ""),
        tostring(status.fingerprintSha256 or "")
    }, "\31")
end

local function copy(values)
    local result = {}
    if type(values) == "table" then
        for key, value in pairs(values) do result[key] = value end
    end
    return result
end

local function statusValue(status, key, fallback)
    if type(status) ~= "table" then return fallback end
    local value = status[key]
    return value == nil and fallback or tostring(value)
end

local function invoke(api, name, ...)
    local fn = type(api) == "table" and api[name] or nil
    if type(fn) ~= "function" then return false, nil end
    return pcall(fn, ...)
end

local function handshakeRequest(config, enabled)
    if enabled == false then
        return {
            workshopId = Protocol.WORKSHOP_ID,
            luaModId = Protocol.MOD_ID,
            bridgeProtocol = Protocol.BRIDGE_PROTOCOL,
            nativeAssistEnabled = false,
            serverRewindEnabled = false,
            pvpRewindEnabled = false,
            pveRewindEnabled = false,
            playerNativeAssistEnabled = false,
            zombieCombatBubbleEnabled = false,
            vehicleNativeAssistEnabled = false,
            directPlayerCorrection = false,
            directVehicleCorrection = false,
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
            diagnosticsIntervalSeconds = 60
        }
    end
    return {
        workshopId = Protocol.WORKSHOP_ID,
        luaModId = Protocol.MOD_ID,
        bridgeProtocol = Protocol.BRIDGE_PROTOCOL,
        nativeAssistEnabled = config.nativeAssistEnabled,
        serverRewindEnabled = config.serverRewindEnabled,
        pvpRewindEnabled = config.pvpRewind,
        pveRewindEnabled = config.pveRewind,
        playerNativeAssistEnabled = config.playerNativeAssist,
        zombieCombatBubbleEnabled = config.zombieCombatBubble,
        vehicleNativeAssistEnabled = config.vehicleNativeAssist,
        directPlayerCorrection = config.directPlayerCorrection,
        directVehicleCorrection = config.directVehicleCorrection,
        historyMs = config.historyMs,
        combatSampleMs = config.combatSampleMs,
        maxRewindMs = config.maxRewindMs,
        hardMaxRewindMs = config.hardMaxRewindMs,
        rttCutoffMs = config.rttCutoffMs,
        jitterCutoffMs = config.jitterCutoffMs,
        rangeEpsilonTiles = config.rangeEpsilonTiles,
        divergenceRejectTiles = config.divergenceRejectTiles,
        combatBubbleInnerRadius = config.combatBubbleInnerRadius,
        combatBubbleOuterRadius = config.combatBubbleOuterRadius,
        combatBubbleMaxPlayers = config.combatBubbleMaxPlayers,
        combatBubbleMaxZombies = config.combatBubbleMaxZombies,
        diagnosticsIntervalSeconds = config.diagnosticsIntervalSeconds
    }
end

function NativeAssistServer.new(api, config)
    return setmetatable({
        api = type(api) == "table" and api or {},
        config = config,
        state = "ABSENT",
        reasonCode = "bridge-not-found",
        bridgeProtocol = "",
        fingerprintSha256 = "",
        bridge = nil,
        handshakeAttempted = false,
        handshakeAccepted = false,
        acceptedBridge = nil,
        acceptedConfigSignature = nil,
        acceptedStatusSignature = nil,
        lastConfigSignature = nil,
        lastStatusSignature = nil,
        initialized = false,
        loggedStates = {},
        loggedFaults = {},
        deauthorizing = false,
        deauthorizationSent = false
    }, NativeAssistServer)
end

function NativeAssistServer:deauthorizeBridge(bridge)
    if bridge == nil or self.deauthorizing then return end
    self.deauthorizing = true
    self.deauthorizationSent = true
    local ok = invoke(self.api, "nativeBridgeHandshake", bridge,
        handshakeRequest(nil, false))
    self.deauthorizing = false
    if not ok and not self.loggedFaults["bridge-deauthorization-error"] then
        self.loggedFaults["bridge-deauthorization-error"] = true
        if type(self.api.logNativeAssistState) == "function" then
            pcall(self.api.logNativeAssistState, {
                state = "CIRCUIT_OPEN",
                reasonCode = "bridge-deauthorization-error",
                bridgeProtocol = self.bridgeProtocol
            })
        end
    end
end

function NativeAssistServer:invalidateHandshake(resetAttempt, notifyBridge)
    local acceptedBridge = self.acceptedBridge
    local wasAccepted = self.handshakeAccepted == true or acceptedBridge ~= nil
    self.handshakeAccepted = false
    self.acceptedBridge = nil
    self.acceptedConfigSignature = nil
    self.acceptedStatusSignature = nil
    if resetAttempt then self.handshakeAttempted = false end
    if notifyBridge and wasAccepted then
        self:deauthorizeBridge(acceptedBridge or self.bridge)
    end
end

function NativeAssistServer:setStatus(status)
    local state = statusValue(status, "state", "CIRCUIT_OPEN")
    if not STATES[state] then state = "CIRCUIT_OPEN" end
    self.state = state
    self.reasonCode = statusValue(status, "reasonCode", "bridge-status-invalid")
    self.bridgeProtocol = statusValue(status, "bridgeProtocol", "")
    self.fingerprintSha256 = statusValue(status, "fingerprintSha256", "")
    if not self.loggedStates[state] then
        self.loggedStates[state] = true
        if type(self.api.logNativeAssistState) == "function" then
            pcall(self.api.logNativeAssistState, {
                state = self.state,
                reasonCode = self.reasonCode,
                bridgeProtocol = self.bridgeProtocol
            })
        end
    end
end

function NativeAssistServer:handshake()
    self.handshakeAttempted = true
    self:invalidateHandshake(false, false)
    local request = handshakeRequest(self.config, true)
    local ok, status = invoke(self.api, "nativeBridgeHandshake", self.bridge, request)
    if not ok or type(status) ~= "table" then
        self:deauthorizeBridge(self.bridge)
        self:setStatus({ state = "CIRCUIT_OPEN", reasonCode = "bridge-handshake-error" })
        self.lastStatusSignature = statusSignature(self)
        return
    end
    self:setStatus(status)
    self.lastStatusSignature = statusSignature(self)
    self.handshakeAccepted = self.state == "READY"
        and self.bridgeProtocol == Protocol.BRIDGE_PROTOCOL
    if self.handshakeAccepted then
        self.deauthorizationSent = false
        self.acceptedBridge = self.bridge
        self.acceptedConfigSignature = configSignature(self.config)
        self.acceptedStatusSignature = self.lastStatusSignature
    else
        self:invalidateHandshake(false, false)
    end
end

function NativeAssistServer:initialize()
    self.initialized = true
    self:refresh()
    return self
end

function NativeAssistServer:refresh()
    if not self.initialized then return self:initialize() end
    local valid, reason = Validation.nativeAssistConfig(self.config)
    if not valid then
        self:invalidateHandshake(true, true)
        self:setStatus({ state = "INCOMPATIBLE", reasonCode = reason })
        return self
    end
    local currentConfigSignature = configSignature(self.config)
    if currentConfigSignature ~= self.lastConfigSignature then
        self:invalidateHandshake(true, true)
        self.lastConfigSignature = currentConfigSignature
    end
    if self.config.nativeAssistEnabled == false then
        self:invalidateHandshake(true, true)
        self:setStatus({ state = "DISABLED", reasonCode = "native-assist-disabled" })
        return self
    end
    local ok, bridge = invoke(self.api, "getNativeBridge")
    if not ok or bridge == nil then
        self:invalidateHandshake(true, true)
        self.bridge = nil
        self:setStatus({ state = "ABSENT", reasonCode = "bridge-not-found" })
        return self
    end
    if bridge ~= self.bridge then
        self:invalidateHandshake(true, true)
        self.bridge = bridge
        self.lastStatusSignature = nil
    end
    local statusOk, status = invoke(self.api, "nativeBridgeStatus", bridge)
    if not statusOk or type(status) ~= "table" then
        self:invalidateHandshake(true, true)
        self:setStatus({ state = "CIRCUIT_OPEN", reasonCode = "bridge-status-error" })
        return self
    end
    self:setStatus(status)
    local currentStatusSignature = statusSignature(self)
    if currentStatusSignature ~= self.lastStatusSignature then
        self:invalidateHandshake(true, true)
        self.lastStatusSignature = currentStatusSignature
    end
    local recoveringFromDeauthorization = self.deauthorizationSent
        and self.state == "DISABLED"
        and self.reasonCode == "config-disabled"
    if self.state ~= "READY" and self.state ~= "ABSENT"
            and not recoveringFromDeauthorization then
        self:invalidateHandshake(true, true)
        return self
    end
    if self.bridgeProtocol ~= Protocol.BRIDGE_PROTOCOL then
        self:invalidateHandshake(true, true)
        return self
    end
    if not self.handshakeAttempted then
        self:handshake()
    end
    return self
end

function NativeAssistServer:allowsDecisions()
    local valid = Validation.nativeAssistConfig(self.config)
    local allowed = valid == true
        and self.config.nativeAssistEnabled == true
        and self.handshakeAccepted == true
        and self.state == "READY"
        and self.bridgeProtocol == Protocol.BRIDGE_PROTOCOL
        and self.bridge == self.acceptedBridge
        and configSignature(self.config) == self.acceptedConfigSignature
        and statusSignature(self) == self.acceptedStatusSignature
    if not allowed and (self.handshakeAccepted or self.acceptedBridge ~= nil) then
        self:invalidateHandshake(true, true)
    end
    return allowed
end

function NativeAssistServer:collectDiagnostics()
    local metrics = {}
    if self.bridge ~= nil then
        local ok, values = invoke(self.api, "nativeBridgeMetrics", self.bridge)
        if ok and type(values) == "table" then metrics = copy(values) end
    end
    return {
        state = self.state,
        reasonCode = self.reasonCode,
        enabled = self:allowsDecisions(),
        accepted = {
            total = metrics.hit_gate_accepts or 0,
            rewind = metrics.decision_accept_rewind or 0,
            current = metrics.decision_accept_current or 0
        },
        rejected = {
            total = metrics.hit_gate_rejections or 0,
            identity = metrics.decision_reject_identity or 0,
            floor = metrics.decision_reject_floor or 0,
            range = metrics.decision_reject_range or 0,
            cone = metrics.decision_reject_cone or 0,
            los = metrics.decision_reject_los or 0,
            divergence = metrics.decision_reject_divergence or 0,
            unloaded = metrics.decision_reject_unloaded or 0,
            stale = metrics.decision_reject_stale or 0,
            native = metrics.decision_reject_native or 0
        },
        historyMisses = metrics.history_misses or 0,
        rttFallbacks = metrics.rewind_fallback_rtt or 0,
        jitterFallbacks = metrics.rewind_fallback_jitter or 0,
        bubblePopulation = {
            active = metrics.bubble_active or 0,
            players = metrics.bubble_players or 0,
            zombies = metrics.bubble_zombies or 0
        },
        handoffs = metrics.zombie_handoffs or 0,
        circuitState = math.max(metrics.circuit_open or 0,
            self.state == "CIRCUIT_OPEN" and 1 or 0),
        counters = metrics
    }
end

local function valueAt(object, key)
    if object == nil then return nil end
    local ok, value = pcall(function() return object[key] end)
    return ok and value or nil
end

local function callValue(callable, argument, hasArgument)
    if type(callable) ~= "function" then return false, nil end
    if hasArgument then return pcall(callable, argument) end
    return pcall(callable)
end

local function exportCall(bridge, name, argument, hasArgument)
    return callValue(valueAt(bridge, name), argument, hasArgument)
end

local function normalizeStatus(status)
    if type(status) ~= "table" then error("native status table required") end
    return {
        state = tostring(status.state or "CIRCUIT_OPEN"),
        reasonCode = tostring(status.reasonCode or "bridge-status-invalid"),
        bridgeProtocol = tostring(status.bridgeProtocol or ""),
        fingerprintSha256 = tostring(status.fingerprintSha256 or "")
    }
end

local function newHandshake(values)
    if type(values) ~= "table" then return nil end
    return {
        workshopId = values.workshopId,
        luaModId = values.luaModId,
        bridgeProtocol = values.bridgeProtocol,
        nativeAssistEnabled = values.nativeAssistEnabled,
        serverRewindEnabled = values.serverRewindEnabled,
        pvpRewindEnabled = values.pvpRewindEnabled,
        pveRewindEnabled = values.pveRewindEnabled,
        playerNativeAssistEnabled = values.playerNativeAssistEnabled,
        zombieCombatBubbleEnabled = values.zombieCombatBubbleEnabled,
        vehicleNativeAssistEnabled = values.vehicleNativeAssistEnabled,
        directPlayerCorrection = values.directPlayerCorrection,
        directVehicleCorrection = values.directVehicleCorrection,
        historyMs = values.historyMs,
        combatSampleMs = values.combatSampleMs,
        maxRewindMs = values.maxRewindMs,
        hardMaxRewindMs = values.hardMaxRewindMs,
        rttCutoffMs = values.rttCutoffMs,
        jitterCutoffMs = values.jitterCutoffMs,
        rangeEpsilonTiles = values.rangeEpsilonTiles,
        divergenceRejectTiles = values.divergenceRejectTiles,
        combatBubbleInnerRadius = values.combatBubbleInnerRadius,
        combatBubbleOuterRadius = values.combatBubbleOuterRadius,
        combatBubbleMaxPlayers = values.combatBubbleMaxPlayers,
        combatBubbleMaxZombies = values.combatBubbleMaxZombies,
        diagnosticsIntervalSeconds = values.diagnosticsIntervalSeconds
    }
end

local function metricsCopy(values)
    if type(values) ~= "table" then error("native metrics table required") end
    local result = {}
    for key, value in pairs(values) do
        local numeric = tonumber(value)
        if numeric == nil then error("invalid native metric value") end
        result[tostring(key)] = numeric
    end
    return result
end

function NativeAssistServer.decorateApi(api)
    if type(api.getNativeBridge) ~= "function" then
        function api.getNativeBridge() return rawget(_G, "ApolloNativeAssist") end
    end
    if type(api.nativeBridgeStatus) ~= "function" then
        function api.nativeBridgeStatus(bridge)
            local ok, status = exportCall(bridge, "status", nil, false)
            if not ok then error("native status unavailable") end
            return normalizeStatus(status)
        end
    end
    if type(api.nativeBridgeHandshake) ~= "function" then
        function api.nativeBridgeHandshake(bridge, values)
            local handshake = newHandshake(values)
            if handshake == nil then error("native handshake type unavailable") end
            local ok, status = exportCall(bridge, "handshake", handshake, true)
            if not ok then error("native handshake unavailable") end
            return normalizeStatus(status)
        end
    end
    if type(api.nativeBridgeMetrics) ~= "function" then
        function api.nativeBridgeMetrics(bridge)
            local ok, metrics = exportCall(bridge, "metrics", nil, false)
            if not ok then error("native metrics unavailable") end
            return metricsCopy(metrics)
        end
    end
    if type(api.logNativeAssistState) ~= "function" then
        function api.logNativeAssistState(status)
            local fn = rawget(_G, "print")
            if type(fn) == "function" then
                fn("[ApolloMPSync] native-assist state=" .. tostring(status.state)
                    .. " reason=" .. tostring(status.reasonCode))
            end
        end
    end
    return api
end

return NativeAssistServer
