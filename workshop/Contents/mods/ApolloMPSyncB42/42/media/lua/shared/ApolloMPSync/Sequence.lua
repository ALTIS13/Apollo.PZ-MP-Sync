local Sequence = {}

local function isSequence(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
        and value >= 0
        and value <= 2147483647
        and value == math.floor(value)
end

function Sequence.new()
    return {}
end

function Sequence.accept(state, epoch, seq)
    if type(epoch) ~= "string" or epoch == "" then
        return false, "invalid-epoch"
    end

    if not isSequence(seq) then
        return false, "sequence"
    end

    if state.epoch == nil then
        state.epoch = epoch
        state.seq = seq
        return true, "first"
    end

    if state.epoch ~= epoch then
        state.epoch = epoch
        state.seq = seq
        return true, "epoch-reset"
    end

    if seq <= state.seq then
        return false, "stale"
    end

    state.seq = seq
    return true, "ok"
end

return Sequence
