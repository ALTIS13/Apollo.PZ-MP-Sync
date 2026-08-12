local Protocol = {
    MOD_ID = "ApolloMPSyncB42",
    WORKSHOP_ID = "3780069702",
    BRIDGE_PROTOCOL = "1",
    VERSION = 1,
    NAMESPACE = "ApolloMPSync",
    CHANNELS = {
        player = "player",
        action = "action",
        vehicle = "vehicle",
        trailer = "trailer",
        parts = "parts",
        ping = "ping"
    }
}

function Protocol.isCompatible(payload)
    return type(payload) == "table"
        and (payload.protocolVersion == nil or payload.protocolVersion == Protocol.VERSION)
end

return Protocol
