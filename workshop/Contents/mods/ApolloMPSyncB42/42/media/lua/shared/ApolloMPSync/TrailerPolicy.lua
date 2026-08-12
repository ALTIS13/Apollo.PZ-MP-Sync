local VehiclePolicy = require("ApolloMPSync/VehiclePolicy")

local TrailerPolicy = {}

local function isFinite(value)
    return type(value) == "number"
        and value == value
        and value ~= math.huge
        and value ~= -math.huge
end

local function nonEmptyString(value)
    return type(value) == "string" and value ~= ""
end

local function validEvent(event)
    return type(event) == "table"
        and (event.kind == "attach" or event.kind == "detach")
        and nonEmptyString(event.towingVehicleId)
        and nonEmptyString(event.trailerId)
        and isFinite(event.sequence) and event.sequence >= 0 and event.sequence == math.floor(event.sequence)
end

local function removeTrailer(chain, trailerId)
    for index = #chain.trailers, 1, -1 do
        if chain.trailers[index] == trailerId then
            table.remove(chain.trailers, index)
        end
    end
end

-- senderDriverId comes from the event sender; authorityDriverId is server-derived.
-- Adapters must supply both so this policy can reject non-driver attach/detach events.
function TrailerPolicy.apply(state, event)
    if type(state) ~= "table" or not validEvent(event) then
        return state, "invalid"
    end

    if not nonEmptyString(event.senderDriverId) or not nonEmptyString(event.authorityDriverId) then
        return state, "identity"
    end

    if event.senderDriverId ~= event.authorityDriverId then
        return state, "authority"
    end

    state.towChains = state.towChains or {}
    state.trailers = state.trailers or {}
    state.lastTrailerSequences = state.lastTrailerSequences or {}

    local previous = state.lastTrailerSequences[event.trailerId]
    if previous ~= nil and event.sequence <= previous then
        return state, "stale"
    end

    state.lastTrailerSequences[event.trailerId] = event.sequence
    local chain = state.towChains[event.towingVehicleId]
    if chain == nil then
        chain = { vehicleId = event.towingVehicleId, trailers = {} }
        state.towChains[event.towingVehicleId] = chain
    end

    if event.kind == "attach" then
        local trailer = state.trailers[event.trailerId]
        if trailer and trailer.attachedTo and trailer.attachedTo ~= event.towingVehicleId then
            local oldChain = state.towChains[trailer.attachedTo]
            if oldChain then
                removeTrailer(oldChain, event.trailerId)
            end
        end
        removeTrailer(chain, event.trailerId)
        chain.trailers[#chain.trailers + 1] = event.trailerId
        state.trailers[event.trailerId] = {
            attachedTo = event.towingVehicleId,
            authorityOwnerId = event.senderDriverId,
            authorityEpoch = event.epoch or event.sequence,
            lastSequence = event.sequence
        }
    else
        removeTrailer(chain, event.trailerId)
        local trailer = state.trailers[event.trailerId]
        if trailer and trailer.attachedTo and trailer.attachedTo ~= event.towingVehicleId then
            local oldChain = state.towChains[trailer.attachedTo]
            if oldChain then
                removeTrailer(oldChain, event.trailerId)
            end
        end
        state.trailers[event.trailerId] = {
            attachedTo = nil,
            transform = VehiclePolicy.sanitizeTransform(event.transform),
            authorityOwnerId = event.senderDriverId,
            authorityEpoch = event.epoch or event.sequence,
            lastSequence = event.sequence
        }
    end

    return state, "ok"
end

return TrailerPolicy
