local A = require("tests.support.assertions")
local ActionRegistry = require("ApolloMPSync/ActionRegistry")
local ActionPolicy = require("ApolloMPSync/ActionPolicy")

local function action(className, phase, extra)
    local snapshot = {
        className = className,
        phase = phase,
        anchor = { x = 10, y = 20, z = 0, facing = 90 },
        animationVars = { PerformingAction = true },
        progress = 0.5
    }

    if extra ~= nil then
        for key, value in pairs(extra) do
            snapshot[key] = value
        end
    end

    return snapshot
end

-- Break caught: an adapter resolving a supported action class to generic busy.
local builtIns = {
    { "ISSleepAction", "sleep" },
    { "ISReadABook", "read" },
    { "ISSitOnGround", "sit" },
    { "ISRestAction", "rest" },
    { "ISEnterVehicle", "vehicle-enter" },
    { "ISExitVehicle", "vehicle-exit" },
    { "ISClimbThroughWindow", "climb-window" },
    { "ISClimbOverFence", "climb-fence" },
    { "ISClimbLadder", "climb-ladder" },
    { "ISFloorTransition", "floor-transition" },
    { "ISBaseTimedAction", "timed-action" }
}

for _, case in ipairs(builtIns) do
    local descriptor = ActionRegistry.resolve(case[1])
    A.equal(descriptor.key, case[2])
    A.equal(descriptor.category, case[2])
end

-- Break caught: an unknown extension gaining a privileged action category or animation variable.
local generic = ActionRegistry.resolve("UntrustedTimedAction")
A.equal(generic.key, "generic-busy")
A.equal(generic.category, "timed-action")
A.equal(generic.animationVars[1], "PerformingAction")

-- Break caught: an extension replacing the registry's canonical generic fallback.
local reservedFallback, reservedReason = ActionRegistry.register("generic-busy", {
    key = "generic-busy",
    category = "timed-action",
    classes = { "AttemptedGenericBusyOverride" },
    animationVars = { "PerformingAction" },
    anchorMode = "player"
})
A.equal(reservedFallback, false)
A.equal(reservedReason, "duplicate-key")

local unsafeDescriptor = {
    key = "unsafe-extension",
    category = "timed-action",
    classes = { "UnsafeAction" },
    animationVars = { "PerformingAction" },
    anchorMode = "player",
    lua = "return unsafe"
}
A.equal(ActionRegistry.register("unsafe-extension", unsafeDescriptor), false)

local invalidAnimationDescriptor = {
    key = "bad-animation-extension",
    category = "timed-action",
    classes = { "BadAnimationAction" },
    animationVars = { "DoAnything" },
    anchorMode = "player"
}
A.equal(ActionRegistry.register("bad-animation-extension", invalidAnimationDescriptor), false)

local extension = {
    key = "sort-items",
    category = "timed-action",
    classes = { "ISSortItems" },
    animationVars = { "PerformingAction" },
    anchorMode = "player"
}
A.equal(ActionRegistry.register("sort-items", extension), true)
A.equal(ActionRegistry.resolve("ISSortItems").key, "sort-items")
A.equal(ActionRegistry.register("sort-items", extension), false)

-- Break caught: treating a Sleep with Friends class as sleep before the adapter observed it.
A.equal(ActionRegistry.resolve("ISSleepWithFriendsAction").key, "generic-busy")
A.equal(ActionRegistry.registerSleepWithFriends({ "ISSleepWithFriendsAction" }), true)
A.equal(ActionRegistry.resolve("ISSleepWithFriendsAction").key, "sleep")

-- Break caught: relaying progress faster than the default 500 ms interval.
local progressState = {}
A.equal(ActionPolicy.accept(progressState, action("ISReadABook", "start"), 0, {}).phase, "start")
local throttled, throttleReason = ActionPolicy.accept(progressState, action("ISReadABook", "progress"), 499, {})
A.equal(throttled, nil)
A.equal(throttleReason, "throttled")
A.equal(ActionPolicy.accept(progressState, action("ISReadABook", "progress"), 500, {}).phase, "progress")

-- Break caught: accepting a progress event that cannot describe normalized progress.
local progressWithoutValue = action("ISReadABook", "progress")
progressWithoutValue.progress = nil
local missingProgress, missingProgressReason = ActionPolicy.accept(progressState, progressWithoutValue, 1000, {})
A.equal(missingProgress, nil)
A.equal(missingProgressReason, "progress")

-- Break caught: replacing an active semantic action before it completes or cancels.
local lifecycleState = {}
ActionPolicy.accept(lifecycleState, action("ISReadABook", "start"), 0, {})
local overlappingStart, overlappingReason = ActionPolicy.accept(lifecycleState, action("ISSitOnGround", "start"), 1, {})
A.equal(overlappingStart, nil)
A.equal(overlappingReason, "lifecycle")

-- Break caught: completing or cancelling without starting the 1250 ms movement grace period.
local cancelState = {}
ActionPolicy.accept(cancelState, action("ISReadABook", "start"), 0, {})
local cancelled = ActionPolicy.accept(cancelState, action("ISReadABook", "cancel"), 100, {})
A.equal(cancelled.graceUntilMs, 1350)
A.equal(cancelState.graceUntilMs, 1350)

local completeState = {}
ActionPolicy.accept(completeState, action("ISReadABook", "start"), 0, {})
local completed = ActionPolicy.accept(completeState, action("ISReadABook", "complete"), 100, {})
A.equal(completed.graceUntilMs, 1350)
A.equal(completeState.graceUntilMs, 1350)

-- Break caught: emitting a sleep action when the administrator disabled sleep synchronization.
local disabled, disabledReason = ActionPolicy.accept({}, action("ISSleepAction", "start"), 0, {
    sleepEnabled = false
})
A.equal(disabled, nil)
A.equal(disabledReason, "disabled")

-- Break caught: forwarding an animation variable that the descriptor did not allow.
local sanitized = ActionPolicy.accept({}, action("ISReadABook", "start", {
    animationVars = { PerformingAction = true, IsReading = true, DoAnything = true }
}), 0, {})
A.equal(sanitized.animationVars.PerformingAction, true)
A.equal(sanitized.animationVars.IsReading, true)
A.equal(sanitized.animationVars.DoAnything, nil)

-- Break caught: a gameplay-result payload reaching the semantic relay.
local forbiddenPayloadKeys = { "lua", "inventory", "xp", "damage", "item", "recipe" }
for _, key in ipairs(forbiddenPayloadKeys) do
    local rejected, reason = ActionPolicy.accept({}, action("ISReadABook", "start", {
        payload = { [key] = "unsafe" }
    }), 0, {})
    A.equal(rejected, nil)
    A.equal(reason, "payload")
end

-- Break caught: a direct gameplay-result field advancing lifecycle state before rejection.
for _, key in ipairs(forbiddenPayloadKeys) do
    local directState = {}
    local directSnapshot = action("ISReadABook", "start")
    directSnapshot[key] = "unsafe"
    local rejected, reason = ActionPolicy.accept(directState, directSnapshot, 0, {})
    A.equal(rejected, nil)
    A.equal(reason, "payload")
    A.equal(directState.activeKey, nil)
end

-- Break caught: exposing a caller payload or gameplay-result field after a valid action is accepted.
A.equal(sanitized.payload, nil)
for _, key in ipairs(forbiddenPayloadKeys) do
    A.equal(sanitized[key], nil)
end

print("PASS actions")
