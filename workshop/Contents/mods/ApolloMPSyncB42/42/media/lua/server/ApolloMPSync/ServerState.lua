local ServerState = {}

function ServerState.new()
    return {
        playerPolicyById = {},
        actionPolicyByPlayerId = {},
        vehiclePolicyById = {},
        trailerPolicyById = {},
        rateLimitByPlayerId = {},
        disabledCategories = {}
    }
end

return ServerState
