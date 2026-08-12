local A = require("tests.support.assertions")
local Sequence = require("ApolloMPSync/Sequence")
local Validation = require("ApolloMPSync/Validation")
local RateLimit = require("ApolloMPSync/RateLimit")
local AdaptiveRate = require("ApolloMPSync/AdaptiveRate")

local function same(actual, expected)
    A.equal(#actual, #expected)

    for index = 1, #expected do
        A.equal(actual[index], expected[index])
    end
end

-- Break caught: accepting duplicate, older, or out-of-range sequence packets.
local sequence = Sequence.new()
local sequenceCases = {
    { "first packet", "boot-a", 1, { true, "first" } },
    { "duplicate packet", "boot-a", 1, { false, "stale" } },
    { "older packet", "boot-a", 0, { false, "stale" } },
    { "epoch reset", "boot-b", 0, { true, "epoch-reset" } },
    { "negative sequence", "boot-b", -1, { false, "sequence" } },
    { "fractional sequence", "boot-b", 1.5, { false, "sequence" } },
    { "nan sequence", "boot-b", 0 / 0, { false, "sequence" } },
    { "infinite sequence", "boot-b", 1 / 0, { false, "sequence" } },
    { "large sequence", "boot-b", 2147483648, { false, "sequence" } }
}

for _, case in ipairs(sequenceCases) do
    same({ Sequence.accept(sequence, case[2], case[3]) }, case[4])
end

-- Break caught: allowing malformed epochs to mutate sequence state.
local invalidEpoch = Sequence.new()
same({ Sequence.accept(invalidEpoch, nil, 1) }, { false, "invalid-epoch" })
same({ Sequence.accept(invalidEpoch, "", 1) }, { false, "invalid-epoch" })
A.equal(invalidEpoch.epoch, nil)
A.equal(invalidEpoch.seq, nil)
same({ Sequence.accept(invalidEpoch, "boot-valid", 0) }, { true, "first" })

-- Break caught: accepting malformed transforms, another player's transform, or an impossible jump.
local observed = { id = "7656119", x = 10, y = 20, z = 0 }
local playerCases = {
    { "valid player snapshot", { id = "7656119", x = 11, y = 20, z = 0 }, { true, "ok" } },
    { "nan coordinate", { id = "7656119", x = 0 / 0, y = 20, z = 0 }, { false, "coordinate" } },
    { "infinite coordinate", { id = "7656119", x = 1 / 0, y = 20, z = 0 }, { false, "coordinate" } },
    { "coordinate below bound", { id = "7656119", x = -300001, y = 20, z = 0 }, { false, "coordinate" } },
    { "coordinate above bound", { id = "7656119", x = 300001, y = 20, z = 0 }, { false, "coordinate" } },
    { "missing identity", { x = 11, y = 20, z = 0 }, { false, "identity" } },
    { "identity mismatch", { id = "other", x = 11, y = 20, z = 0 }, { false, "identity" } },
    { "floor below bound", { id = "7656119", x = 11, y = 20, z = -33 }, { false, "floor" } },
    { "floor above bound", { id = "7656119", x = 11, y = 20, z = 33 }, { false, "floor" } },
    { "one floor transition", { id = "7656119", x = 11, y = 20, z = 1 }, { true, "ok" } },
    { "two floor jump", { id = "7656119", x = 11, y = 20, z = 2 }, { false, "jump" } },
    { "25 tile horizontal jump", { id = "7656119", x = 35, y = 20, z = 0 }, { true, "ok" } },
    { "over 25 tile horizontal jump", { id = "7656119", x = 36, y = 20, z = 0 }, { false, "jump" } }
}

for _, case in ipairs(playerCases) do
    same({ Validation.player(case[2], observed) }, case[3])
end
same({ Validation.player({ id = "7656119", x = 11, y = 20, z = 0 }, { id = "7656119", x = 1 / 0, y = 20, z = 0 }) }, { false, "observed" })
same({ Validation.player({ x = 11, y = 20, z = 0 }, { x = 10, y = 20, z = 0 }) }, { false, "identity" })

-- Break caught: accepting a server interval shorter than 100 ms or leaking rate state between keys.
local rate = {}
local rateCases = {
    { "first", "7656119:player", 1000, 100, { true, "first" } },
    { "before minimum", "7656119:player", 1099, 100, { false, "rate" } },
    { "at minimum", "7656119:player", 1100, 100, { true, "ok" } },
    { "server minimum", "7656119:vehicle", 1000, 10, { true, "first" } },
    { "server minimum enforced", "7656119:vehicle", 1099, 10, { false, "rate" } }
}

for _, case in ipairs(rateCases) do
    same({ RateLimit.accept(rate, case[2], case[3], case[4]) }, case[5])
end
same({ RateLimit.accept({}, "", 1000, 100) }, { false, "key" })
same({ RateLimit.accept({}, "bad-time", 0 / 0, 100) }, { false, "time" })

-- Break caught: deriving intervals from raw rather than smoothed ping, or exceeding mode clamps.
local adaptiveCases = {
    { "player lower clamp", {}, 0, "player", 150 },
    { "player upper clamp", {}, 5000, "player", 350 },
    { "vehicle lower clamp", {}, 0, "vehicle", 100 },
    { "vehicle upper clamp", {}, 5000, "vehicle", 250 }
}

for _, case in ipairs(adaptiveCases) do
    A.equal(AdaptiveRate.update(case[2], case[3], case[4]), case[5])
end

A.equal(AdaptiveRate.update({}, 0 / 0, "player"), 150)
A.equal(AdaptiveRate.update({}, 1 / 0, "player"), 150)
A.equal(AdaptiveRate.update({}, 6000, "player"), 350)

local smoothing = {}
A.equal(AdaptiveRate.update(smoothing, 400, "player"), 350)
A.equal(AdaptiveRate.update(smoothing, 0, "player"), 310)

print("PASS protocol core")
