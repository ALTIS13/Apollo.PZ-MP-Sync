local A = require("tests.support.assertions")
local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")
local TrailerPolicy = require("ApolloMPSync/TrailerPolicy")
local WorkBudget = require("ApolloMPSync/WorkBudget")

local function transform(x, y)
    return {
        x = x,
        y = y,
        z = 0,
        angle = 0,
        vx = 0,
        vy = 0,
        angularVelocity = 0,
        moving = false
    }
end

local function authority(state, vehicleId, driverId, epoch)
    local accepted, reason = VehiclePolicy.acceptAuthority(state, vehicleId, driverId, epoch, epoch * 100)
    A.equal(accepted, true)
    A.equal(reason, "ok")
end

local function report(vehicleId, driverId, epoch, x, y, extras)
    local value = {
        vehicleId = vehicleId,
        driverId = driverId,
        epoch = epoch,
        transform = transform(x, y)
    }

    if extras then
        for key, extra in pairs(extras) do
            value[key] = extra
        end
    end

    return value
end

local function observer(x, y, localDriver)
    return {
        id = "observer",
        x = x,
        y = y,
        z = 0,
        isLocalDriver = localDriver == true,
        transform = transform(0, 0)
    }
end

-- Break caught: accepting a transform from anyone other than the server-ordered driver.
local unauthorized = {}
authority(unauthorized, "car-1", "driver-a", 1)
local rejected = VehiclePolicy.reconcile(unauthorized, observer(0, 0),
    report("car-1", "driver-b", 1, 6, 0, { sequence = 1 }), 100, {})
A.equal(rejected.kind, "none")
A.equal(rejected.reason, "authority")

-- Break caught: replacing the current driver with an older authority epoch.
local handoff = {}
authority(handoff, "car-2", "driver-a", 4)
local staleAccepted, staleReason = VehiclePolicy.acceptAuthority(handoff, "car-2", "driver-b", 3, 500)
A.equal(staleAccepted, false)
A.equal(staleReason, "stale")
authority(handoff, "car-2", "driver-b", 5)
local oldDriver = VehiclePolicy.reconcile(handoff, observer(0, 0),
    report("car-2", "driver-a", 4, 6, 0, { sequence = 1 }), 600, {})
A.equal(oldDriver.kind, "none")
A.equal(oldDriver.reason, "authority")

-- Break caught: overwriting the current driver's local vehicle simulation after confirmations arrive.
local driverOwned = {}
authority(driverOwned, "car-3", "driver-a", 1)
for index = 1, 3 do
    local protected = VehiclePolicy.reconcile(driverOwned, observer(0, 0, true),
        report("car-3", "driver-a", 1, 6, 0, { sequence = index }), index * 100, {})
    A.equal(protected.kind, "none")
    A.equal(protected.reason, "local-driver")
end

-- Break caught: interpolating an observer after fewer than three matching normal-vehicle deltas.
local convergence = {}
authority(convergence, "car-4", "driver-a", 1)
for index = 1, 2 do
    A.equal(VehiclePolicy.reconcile(convergence, observer(0, 0),
        report("car-4", "driver-a", 1, 6, 0, { sequence = index }), index * 100, {}).kind, "none")
end
local interpolated = VehiclePolicy.reconcile(convergence, observer(0, 0),
    report("car-4", "driver-a", 1, 6, 0, { sequence = 3 }), 300, {})
A.equal(interpolated.kind, "interpolate")
A.equal(interpolated.target.x, 6)
A.equal(interpolated.assist, "disabled")

-- Break caught: treating the 100-250 ms active-vehicle send range as an unbounded ping-derived value.
A.equal(VehiclePolicy.interval(0), 100)
A.equal(VehiclePolicy.interval(120), 160)
A.equal(VehiclePolicy.interval(5000), 250)

-- Break caught: reconciling a highway vehicle beyond the hand-derived 300-tile observer relevance radius.
local farAway = {}
authority(farAway, "car-5", "driver-a", 1)
local distant = VehiclePolicy.reconcile(farAway, observer(0, 0),
    report("car-5", "driver-a", 1, 301, 0, { sequence = 1 }), 100, {})
A.equal(distant.kind, "none")
A.equal(distant.reason, "irrelevant")

-- Break caught: falling back to vanilla on a single implausibly large vehicle delta instead of waiting for three confirmations.
local largeDelta = {}
authority(largeDelta, "car-6", "driver-a", 1)
for index = 1, 2 do
    A.equal(VehiclePolicy.reconcile(largeDelta, observer(0, 0),
        report("car-6", "driver-a", 1, 30, 0, { sequence = index }), index * 100, {}).kind, "none")
end
local reset = VehiclePolicy.reconcile(largeDelta, observer(0, 0),
    report("car-6", "driver-a", 1, 30, 0, { sequence = 3 }), 300, {})
A.equal(reset.kind, "vanilla-reset")

-- Break caught: using normal-vehicle confirmation count for the tighter trailer reconciliation path.
local trailerConvergence = {}
authority(trailerConvergence, "car-7", "driver-a", 1)
A.equal(VehiclePolicy.reconcile(trailerConvergence, observer(0, 0),
    report("car-7", "driver-a", 1, 2, 0, { isTrailer = true, sequence = 1 }), 100, {}).kind, "none")
A.equal(VehiclePolicy.reconcile(trailerConvergence, observer(0, 0),
    report("car-7", "driver-a", 1, 2, 0, { isTrailer = true, sequence = 2 }), 200, {}).kind, "interpolate")

-- Break caught: accepting out-of-range transform fields without clamping before the divergence comparison.
local clamped = {}
authority(clamped, "car-8", "driver-a", 1)
for index = 1, 2 do
    VehiclePolicy.reconcile(clamped, observer(300000, -300000), report("car-8", "driver-a", 1, 999999, 0, {
        transform = { x = 999999, y = -999999, z = 99, angle = math.huge, vx = math.huge,
            vy = -math.huge, angularVelocity = 999999, moving = true }, sequence = index
    }), index * 100, {})
end
local normalized = VehiclePolicy.reconcile(clamped, observer(300000, -300000), report("car-8", "driver-a", 1, 999999, 0, {
    transform = { x = 999999, y = -999999, z = 99, angle = math.huge, vx = math.huge,
        vy = -math.huge, angularVelocity = 999999, moving = true }, sequence = 3
}), 400, {})
A.equal(normalized.kind, "vanilla-reset")
A.equal(normalized.target.x, 300000)
A.equal(normalized.target.y, -300000)
A.equal(normalized.target.z, 32)
A.equal(normalized.target.angle, 0)
A.equal(normalized.target.vx, 0)
A.equal(normalized.target.vy, 0)
A.equal(normalized.target.angularVelocity, 720)

-- Break caught: applying an out-of-order detach after a newer attach and losing the complete tow chain.
local chains = {}
local attached, attachReason = TrailerPolicy.apply(chains, {
    kind = "attach", sequence = 10, towingVehicleId = "truck-1", trailerId = "trailer-a",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
A.equal(attachReason, "ok")
A.equal(attached.towChains["truck-1"].trailers[1], "trailer-a")
TrailerPolicy.apply(chains, {
    kind = "attach", sequence = 11, towingVehicleId = "truck-1", trailerId = "trailer-b",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
local staleChain, staleDetach = TrailerPolicy.apply(chains, {
    kind = "detach", sequence = 9, towingVehicleId = "truck-1", trailerId = "trailer-a",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
A.equal(staleDetach, "stale")
A.equal(staleChain.towChains["truck-1"].trailers[1], "trailer-a")
A.equal(staleChain.towChains["truck-1"].trailers[2], "trailer-b")

-- Break caught: leaving a detached trailer governed by the towing vehicle instead of giving it independent state.
local detached, detachReason = TrailerPolicy.apply(chains, {
    kind = "detach", sequence = 12, towingVehicleId = "truck-1", trailerId = "trailer-a",
    epoch = 20, transform = transform(12, 4), senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
A.equal(detachReason, "ok")
A.equal(detached.towChains["truck-1"].trailers[1], "trailer-b")
A.equal(detached.trailers["trailer-a"].attachedTo, nil)
A.equal(detached.trailers["trailer-a"].transform.x, 12)
A.equal(detached.trailers["trailer-a"].authorityOwnerId, "driver-a")
A.equal(detached.trailers["trailer-a"].authorityEpoch, 20)
A.equal(detached.trailers["trailer-a"].lastSequence, 12)

-- Break caught: accepting attach or detach events that do not match the server-derived towing-driver authority.
local securedChains = {}
local rejectedAttach, attachRejectReason = TrailerPolicy.apply(securedChains, {
    kind = "attach", sequence = 1, towingVehicleId = "truck-secure", trailerId = "trailer-secure",
    senderDriverId = "imposter", authorityDriverId = "driver-a"
})
A.equal(attachRejectReason, "authority")
A.equal(rejectedAttach.towChains, nil)
local rejectedDetach, detachRejectReason = TrailerPolicy.apply(securedChains, {
    kind = "detach", sequence = 2, towingVehicleId = "truck-secure", trailerId = "trailer-secure"
})
A.equal(detachRejectReason, "identity")
A.equal(rejectedDetach.towChains, nil)

-- Break caught: moving a trailer to a newer towing chain and later accepting an older attach from its previous chain.
local crossChain = {}
TrailerPolicy.apply(crossChain, {
    kind = "attach", sequence = 5, towingVehicleId = "truck-a", trailerId = "trailer-shared",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
TrailerPolicy.apply(crossChain, {
    kind = "attach", sequence = 6, towingVehicleId = "truck-b", trailerId = "trailer-shared",
    senderDriverId = "driver-b", authorityDriverId = "driver-b"
})
local reordered, reorderReason = TrailerPolicy.apply(crossChain, {
    kind = "attach", sequence = 5, towingVehicleId = "truck-a", trailerId = "trailer-shared",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
A.equal(reorderReason, "stale")
A.equal(#reordered.towChains["truck-a"].trailers, 0)
A.equal(reordered.towChains["truck-b"].trailers[1], "trailer-shared")

-- Break caught: using looser trailer-only numeric handling after a detach than vehicle observer reconciliation uses.
local trailerClamp = {}
TrailerPolicy.apply(trailerClamp, {
    kind = "attach", sequence = 30, towingVehicleId = "truck-clamp", trailerId = "trailer-clamp",
    senderDriverId = "driver-a", authorityDriverId = "driver-a"
})
local clampState = TrailerPolicy.apply(trailerClamp, {
    kind = "detach", sequence = 31, towingVehicleId = "truck-clamp", trailerId = "trailer-clamp",
    senderDriverId = "driver-a", authorityDriverId = "driver-a",
    transform = { x = 999999, y = -999999, z = 99, angle = math.huge, vx = math.huge,
        vy = -math.huge, angularVelocity = 999999, moving = true }
})
A.equal(clampState.trailers["trailer-clamp"].transform.x, 300000)
A.equal(clampState.trailers["trailer-clamp"].transform.y, -300000)
A.equal(clampState.trailers["trailer-clamp"].transform.z, 32)
A.equal(clampState.trailers["trailer-clamp"].transform.angle, 0)
A.equal(clampState.trailers["trailer-clamp"].transform.vx, 0)
A.equal(clampState.trailers["trailer-clamp"].transform.vy, 0)
A.equal(clampState.trailers["trailer-clamp"].transform.angularVelocity, 720)

-- Break caught: counting duplicate or stale vehicle reports toward the three normal-vehicle confirmations.
local freshSamples = {}
authority(freshSamples, "car-fresh", "driver-a", 1)
local missingSequence = VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0), 100, {})
A.equal(missingSequence.kind, "none")
A.equal(missingSequence.reason, "sequence")
A.equal(VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0, { sequence = 1 }), 200, {}).kind, "none")
local duplicateSequence = VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0, { sequence = 1 }), 300, {})
A.equal(duplicateSequence.kind, "none")
A.equal(duplicateSequence.reason, "sequence")
local staleSequence = VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0, { sequence = 0 }), 400, {})
A.equal(staleSequence.kind, "none")
A.equal(staleSequence.reason, "sequence")
A.equal(VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0, { sequence = 2 }), 500, {}).kind, "none")
A.equal(VehiclePolicy.reconcile(freshSamples, observer(0, 0),
    report("car-fresh", "driver-a", 1, 6, 0, { sequence = 3 }), 600, {}).kind, "interpolate")

-- Break caught: accepting body-part repair work outside the hood/trunk/rear-door visual allowlist.
A.equal(VehiclePolicy.isVisualPartAllowed("hood"), true)
A.equal(VehiclePolicy.isVisualPartAllowed("trunk"), true)
A.equal(VehiclePolicy.isVisualPartAllowed("rear-door"), true)
A.equal(VehiclePolicy.isVisualPartAllowed("engine"), false)

-- Break caught: draining more than the configured four of twenty queued vehicle repairs in one tick.
local queue = {}
for index = 1, 20 do
    queue[index] = "vehicle-" .. tostring(index)
end
local repaired = {}
local processed = WorkBudget.drain(queue, 4, function(vehicleId)
    repaired[#repaired + 1] = vehicleId
end)
A.equal(processed, 4)
A.equal(#queue, 16)
A.equal(#repaired, 4)
A.equal(repaired[1], "vehicle-1")
A.equal(repaired[4], "vehicle-4")

print("PASS vehicles")
