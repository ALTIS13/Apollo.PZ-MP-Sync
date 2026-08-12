local A = require("tests.support.assertions")
local Reconciliation = require("ApolloMPSync/Reconciliation")

local function decision(state, localView, report, nowMs)
    return Reconciliation.evaluate(state, localView, report, nowMs, {})
end

local function close(actual, expected)
    if math.abs(actual - expected) > 0.000001 then
        error("expected " .. tostring(expected) .. ", got " .. tostring(actual), 2)
    end
end

local observer = { id = "observer", x = 0, y = 0, z = 0 }

-- Break caught: correcting a remote player whose hand-measured divergence is below 2.5 tiles.
local subThreshold = decision(Reconciliation.new(), observer, {
    id = "remote", x = 2.4, y = 0, z = 0, cellLoaded = true
}, 0)
A.equal(subThreshold.kind, "none")

-- Break caught: correcting before four matching above-threshold reports arrive.
local confirmed = Reconciliation.new()
for index = 1, 3 do
    A.equal(decision(confirmed, observer, {
        id = "remote", x = 3, y = 0, z = 0, cellLoaded = true
    }, index * 100).kind, "none")
end
local fourth = decision(confirmed, observer, {
    id = "remote", x = 3, y = 0, z = 0, cellLoaded = true
}, 400)
A.equal(fourth.kind, "soft")
close(fourth.dx, 0.25)
close(fourth.dy, 0)

-- Break caught: clamping components independently instead of the 3-4-5 correction vector.
local vector = Reconciliation.new()
for index = 1, 3 do
    decision(vector, observer, { id = "remote", x = 3, y = 4, z = 0, cellLoaded = true }, index)
end
local clamped = decision(vector, observer, {
    id = "remote", x = 3, y = 4, z = 0, cellLoaded = true
}, 4)
A.equal(clamped.kind, "soft")
close(clamped.dx, 0.15)
close(clamped.dy, 0.2)

-- Break caught: allowing another correction before the 600 ms base cooldown expires.
local cooldown = Reconciliation.new()
for index = 1, 4 do
    decision(cooldown, observer, { id = "remote", x = 3, y = 0, z = 0, cellLoaded = true }, index * 100)
end
for index = 1, 4 do
    A.equal(decision(cooldown, observer, {
        id = "remote", x = 3, y = 0, z = 0, cellLoaded = true
    }, 500).kind, "none")
end
A.equal(decision(cooldown, observer, {
    id = "remote", x = 3, y = 0, z = 0, cellLoaded = true
}, 1000).kind, "soft")

-- Break caught: ignoring a report's 400 ms latency when extending the correction cooldown to 1000 ms.
local latency = Reconciliation.new()
for index = 1, 4 do
    decision(latency, observer, {
        id = "remote", x = 3, y = 0, z = 0, cellLoaded = true, pingMs = 400
    }, 0)
end
for index = 1, 4 do
    A.equal(decision(latency, observer, {
        id = "remote", x = 3, y = 0, z = 0, cellLoaded = true, pingMs = 400
    }, 999).kind, "none")
end
A.equal(decision(latency, observer, {
    id = "remote", x = 3, y = 0, z = 0, cellLoaded = true, pingMs = 400
}, 1000).kind, "soft")

-- Break caught: treating one action-marked floor outlier as a confirmed floor transition.
local floor = Reconciliation.new()
A.equal(decision(floor, observer, {
    id = "remote", x = 0, y = 0, z = 1, cellLoaded = true, action = "floor-transition"
}, 0).kind, "none")

-- Break caught: missing a floor transition after two sequential matching action-marked reports.
local transition = Reconciliation.new()
decision(transition, observer, {
    id = "remote", x = 0, y = 0, z = 1, cellLoaded = true, action = "climb-window"
}, 0)
local confirmedTransition = decision(transition, observer, {
    id = "remote", x = 0, y = 0, z = 1, cellLoaded = true, action = "climb-window"
}, 100)
A.equal(confirmedTransition.kind, "floor-transition")

-- Break caught: attempting a bounded move instead of yielding to vanilla for an implausible or unloaded target cell.
local fallbackCases = {
    { id = "remote", x = 26, y = 0, z = 0, cellLoaded = true },
    { id = "remote", x = 3, y = 0, z = 0, cellLoaded = false }
}
for _, report in ipairs(fallbackCases) do
    A.equal(decision(Reconciliation.new(), observer, report, 0).kind, "vanilla-reset")
end

-- Break caught: applying network reconciliation to the observer's own player packet.
local selfPacket = decision(Reconciliation.new(), observer, {
    id = "observer", x = 10, y = 0, z = 0, cellLoaded = true
}, 0)
A.equal(selfPacket.kind, "none")

-- Break caught: retaining confirmations after the remote target reverses direction by more than 90 degrees.
local reversed = Reconciliation.new()
for index = 1, 3 do
    decision(reversed, observer, { id = "remote", x = 3, y = 0, z = 0, cellLoaded = true }, index)
end
A.equal(decision(reversed, observer, {
    id = "remote", x = -3, y = 0, z = 0, cellLoaded = true
}, 4).kind, "none")
for index = 5, 6 do
    A.equal(decision(reversed, observer, {
        id = "remote", x = -3, y = 0, z = 0, cellLoaded = true
    }, index).kind, "none")
end
A.equal(decision(reversed, observer, {
    id = "remote", x = -3, y = 0, z = 0, cellLoaded = true
}, 7).kind, "soft")

print("PASS reconciliation")
