module TrapezoidBenesRouteControl #(
    parameter integer LANES = 128,
    parameter integer INDEX_WIDTH = $clog2(LANES),
    parameter integer STAGES = 2 * INDEX_WIDTH - 1,
    parameter integer CONTROL_WIDTH = LANES * STAGES
) (
    input wire clk,
    input wire reset,
    input wire startValid,
    output wire startReady,
    input wire [LANES-1:0] routeValid,
    input wire [LANES*INDEX_WIDTH-1:0] sourceIndices,
    output wire resultValid,
    input wire resultReady,
    output reg [CONTROL_WIDTH-1:0] controls,
    output reg routable,
    output reg [31:0] searchCycles,
    output reg [31:0] backtrackCount
);
    localparam integer DEPTH_WIDTH = (INDEX_WIDTH > 1) ? $clog2(INDEX_WIDTH) : 1;
    localparam integer BASE_WIDTH = $clog2(CONTROL_WIDTH + 1);
    typedef enum logic [3:0] {
        IDLE, INITIALIZE, COLOR_GRAPH, BUILD_CHILDREN, PUSH_LOWER,
        RETRY_ORIENTATION, RETURN_SUCCESS, RETURN_FAILURE, OUTPUT_RESULT
    } state_t;

    state_t state;
    reg [DEPTH_WIDTH-1:0] depth;
    reg [LANES-1:0] frameValid [0:INDEX_WIDTH-1];
    reg [LANES*INDEX_WIDTH-1:0] frameSources [0:INDEX_WIDTH-1];
    reg [BASE_WIDTH-1:0] frameBase [0:INDEX_WIDTH-1];
    reg [LANES-1:0] assigned [0:INDEX_WIDTH-1];
    reg [LANES-1:0] expanded [0:INDEX_WIDTH-1];
    reg [LANES-1:0] baseColor [0:INDEX_WIDTH-1];
    reg [LANES*INDEX_WIDTH-1:0] componentIds [0:INDEX_WIDTH-1];
    reg [INDEX_WIDTH:0] componentCount [0:INDEX_WIDTH-1];
    reg [LANES-1:0] componentFlip [0:INDEX_WIDTH-1];
    reg [LANES-1:0] savedLowerValid [0:INDEX_WIDTH-1];
    reg [LANES*INDEX_WIDTH-1:0] savedLowerSources [0:INDEX_WIDTH-1];
    reg waitingLower [0:INDEX_WIDTH-1];
    reg speculativeOrientation [0:INDEX_WIDTH-1];
    reg [CONTROL_WIDTH-1:0] workingControls;

    integer width;
    integer subnetWidth;
    integer subnetControlWidth;
    integer selectedVertex;
    integer sourceSelected;
    integer sourceOther;
    integer sourcePair;
    integer outputIndex;
    integer inputIndex;
    integer pairIndex;
    integer sequentialIndex;
    logic frontierFound;
    logic seedFound;
    logic graphConflict;
    logic invalidSource;
    logic [LANES-1:0] neighbours;
    logic [LANES-1:0] actualColor;
    logic [LANES-1:0] firstControls;
    logic [LANES-1:0] upperValid;
    logic [LANES-1:0] lowerValid;
    logic [LANES*INDEX_WIDTH-1:0] upperSources;
    logic [LANES*INDEX_WIDTH-1:0] lowerSources;
    logic [LANES-1:0] nextFlip;
    logic hasNextOrientation;
    logic [LANES-1:0] nextAssigned;
    logic [LANES-1:0] nextBaseColor;
    logic [LANES*INDEX_WIDTH-1:0] nextComponentIds;
    logic [11:0] base4Controls;
    logic base4Legal;
    logic frameSingleSource;
    logic [CONTROL_WIDTH-1:0] frameBroadcastControls;
    logic [CONTROL_WIDTH-1:0] frameControlMask;
    integer broadcastLane;
    integer broadcastLevel;
    integer broadcastSource;
    integer broadcastWidth;
    integer broadcastLocalSource;
    integer broadcastBase;
    logic broadcastFound;
    logic fixedOrientationLegal;
    localparam logic [LANES-1:0] FIXED_COLOR = {LANES/2{2'b10}};

    generate if (LANES <= 16) begin : fixed_orientation
        integer requestLane;
        integer inputPair;
        logic [LANES-1:0] upperUsed;
        logic [LANES-1:0] lowerUsed;
        always_comb begin
            upperUsed = '0;
            lowerUsed = '0;
            for (requestLane = 0; requestLane < LANES; requestLane = requestLane + 1) begin
                if (requestLane < width && frameValid[depth][requestLane]) begin
                    if ((requestLane & 1) == 0)
                        upperUsed[frameSources[depth][requestLane*INDEX_WIDTH +: INDEX_WIDTH]] = 1'b1;
                    else
                        lowerUsed[frameSources[depth][requestLane*INDEX_WIDTH +: INDEX_WIDTH]] = 1'b1;
                end
            end
            fixedOrientationLegal = 1'b1;
            for (inputPair = 0; inputPair < LANES/2; inputPair = inputPair + 1) begin
                if ((upperUsed[2*inputPair] && upperUsed[2*inputPair+1]) ||
                    (lowerUsed[2*inputPair] && lowerUsed[2*inputPair+1]))
                    fixedOrientationLegal = 1'b0;
            end
        end
    end else begin : no_fixed_orientation
        assign fixedOrientationLegal = 1'b0;
    end endgenerate

    always_comb begin
        broadcastSource = 0;
        broadcastFound = 1'b0;
        for (broadcastLane = 0; broadcastLane < LANES; broadcastLane = broadcastLane + 1) begin
            if (broadcastLane < width && frameValid[depth][broadcastLane] && !broadcastFound) begin
                broadcastSource = int'(frameSources[depth][broadcastLane*INDEX_WIDTH +: INDEX_WIDTH]);
                broadcastFound = 1'b1;
            end
        end
        frameSingleSource = broadcastFound;
        for (broadcastLane = 0; broadcastLane < LANES; broadcastLane = broadcastLane + 1) begin
            if (broadcastLane < width && frameValid[depth][broadcastLane] &&
                int'(frameSources[depth][broadcastLane*INDEX_WIDTH +: INDEX_WIDTH]) != broadcastSource)
                frameSingleSource = 1'b0;
        end
        frameBroadcastControls = '0;
        broadcastWidth = 0;
        broadcastLocalSource = 0;
        broadcastBase = 0;
        for (broadcastLevel = 0; broadcastLevel < INDEX_WIDTH; broadcastLevel = broadcastLevel + 1) begin
            broadcastWidth = width >> broadcastLevel;
            broadcastLocalSource = broadcastSource >> broadcastLevel;
            broadcastBase = 2 * width - 2 * broadcastWidth;
            if (broadcastWidth > 2)
                frameBroadcastControls[broadcastBase+2*(broadcastLocalSource >> 1)] =
                    (broadcastLocalSource & 1) != 0;
            else if (broadcastWidth == 2) begin
                frameBroadcastControls[broadcastBase] = (broadcastLocalSource & 1) != 0;
                frameBroadcastControls[broadcastBase+1] = (broadcastLocalSource & 1) != 0;
            end
        end
        frameControlMask = {CONTROL_WIDTH{1'b1}} >>
            (CONTROL_WIDTH - width * (STAGES - 2 * depth));
    end

    generate if (LANES >= 4) begin : base_four
        integer candidate;
        integer vertex;
        integer otherVertex;
        integer firstControl;
        integer outputLane;
        integer sourceVertex;
        integer sourceOtherVertex;
        logic candidateLegal;
        always_comb begin
            base4Controls = '0;
            base4Legal = 1'b0;
            candidateLegal = 1'b0;
            sourceVertex = 0;
            sourceOtherVertex = 0;
            for (candidate = 0; candidate < 16; candidate = candidate + 1) begin
                candidateLegal = 1'b1;
                for (vertex = 0; vertex < 4; vertex = vertex + 1) begin
                    for (otherVertex = vertex + 1; otherVertex < 4; otherVertex = otherVertex + 1) begin
                        sourceVertex = int'(frameSources[depth][vertex*INDEX_WIDTH +: INDEX_WIDTH]);
                        sourceOtherVertex = int'(frameSources[depth][otherVertex*INDEX_WIDTH +: INDEX_WIDTH]);
                        if (frameValid[depth][vertex] && frameValid[depth][otherVertex] &&
                            ((((vertex ^ 1) == otherVertex) && sourceVertex != sourceOtherVertex) ||
                             (((sourceVertex >> 1) == (sourceOtherVertex >> 1)) &&
                              ((sourceVertex & 1) != (sourceOtherVertex & 1)))) &&
                            ((candidate >> vertex) & 1) == ((candidate >> otherVertex) & 1))
                            candidateLegal = 1'b0;
                    end
                end
                if (candidateLegal && !base4Legal) begin
                    base4Legal = 1'b1;
                    base4Controls[11:8] = 4'(candidate);
                    for (firstControl = 0; firstControl < 4; firstControl = firstControl + 1) begin
                        for (outputLane = 0; outputLane < 4; outputLane = outputLane + 1) begin
                            sourceVertex = int'(frameSources[depth][outputLane*INDEX_WIDTH +: INDEX_WIDTH]);
                            if (frameValid[depth][outputLane] &&
                                (sourceVertex >> 1) == (firstControl >> 1) &&
                                ((candidate >> outputLane) & 1) == (firstControl & 1))
                                base4Controls[firstControl] = frameSources[depth][outputLane*INDEX_WIDTH];
                        end
                    end
                    for (outputLane = 0; outputLane < 4; outputLane = outputLane + 1) begin
                        if (frameValid[depth][outputLane]) begin
                            if (((candidate >> outputLane) & 1) != 0)
                                base4Controls[6+outputLane/2] = frameSources[depth][outputLane*INDEX_WIDTH+1];
                            else
                                base4Controls[4+outputLane/2] = frameSources[depth][outputLane*INDEX_WIDTH+1];
                        end
                    end
                end
            end
        end
    end else begin : no_base_four
        assign base4Controls = '0;
        assign base4Legal = 1'b0;
    end endgenerate

    assign startReady = state == IDLE;
    assign resultValid = state == OUTPUT_RESULT;

    always_comb begin
        width = LANES >> depth;
        subnetWidth = width >> 1;
        subnetControlWidth = subnetWidth * (STAGES - 2 * depth - 2);
        selectedVertex = 0;
        frontierFound = 1'b0;
        seedFound = 1'b0;
        invalidSource = 1'b0;
        for (inputIndex = 0; inputIndex < LANES; inputIndex = inputIndex + 1) begin
            if (inputIndex < width && frameValid[depth][inputIndex]) begin
                if (int'(frameSources[depth][inputIndex*INDEX_WIDTH +: INDEX_WIDTH]) >= width)
                    invalidSource = 1'b1;
                if (!frontierFound && assigned[depth][inputIndex] &&
                    !expanded[depth][inputIndex]) begin
                    frontierFound = 1'b1;
                    selectedVertex = inputIndex;
                end
            end
        end
        if (!frontierFound) begin
            for (inputIndex = 0; inputIndex < LANES; inputIndex = inputIndex + 1) begin
                if (inputIndex < width && frameValid[depth][inputIndex] &&
                    !assigned[depth][inputIndex] && !seedFound) begin
                    seedFound = 1'b1;
                    selectedVertex = inputIndex;
                end
            end
        end

        sourceSelected = int'(frameSources[depth][selectedVertex*INDEX_WIDTH +: INDEX_WIDTH]);
        sourceOther = 0;
        sourcePair = 0;
        neighbours = '0;
        graphConflict = 1'b0;
        actualColor = '0;
        firstControls = '0;
        upperValid = '0;
        lowerValid = '0;
        upperSources = '0;
        lowerSources = '0;
        nextAssigned = assigned[depth];
        nextBaseColor = baseColor[depth];
        nextComponentIds = componentIds[depth];
        for (inputIndex = 0; inputIndex < LANES; inputIndex = inputIndex + 1) begin
            sourceOther = int'(frameSources[depth][inputIndex*INDEX_WIDTH +: INDEX_WIDTH]);
            neighbours[inputIndex] = (frontierFound || seedFound) && inputIndex < width &&
                frameValid[depth][inputIndex] &&
                ((((selectedVertex ^ 1) == inputIndex) && sourceSelected != sourceOther) ||
                 (((sourceSelected >> 1) == (sourceOther >> 1)) &&
                  ((sourceSelected & 1) != (sourceOther & 1))));
            if (frontierFound && neighbours[inputIndex] && assigned[depth][inputIndex] &&
                baseColor[depth][inputIndex] == baseColor[depth][selectedVertex])
                graphConflict = 1'b1;
            actualColor[inputIndex] = baseColor[depth][inputIndex] ^
                componentFlip[depth][componentIds[depth][inputIndex*INDEX_WIDTH +: INDEX_WIDTH]];
            if (neighbours[inputIndex] && !assigned[depth][inputIndex]) begin
                nextAssigned[inputIndex] = 1'b1;
                nextBaseColor[inputIndex] = seedFound ? 1'b1 : ~baseColor[depth][selectedVertex];
                nextComponentIds[inputIndex*INDEX_WIDTH +: INDEX_WIDTH] =
                    seedFound ? componentCount[depth][INDEX_WIDTH-1:0] :
                    componentIds[depth][selectedVertex*INDEX_WIDTH +: INDEX_WIDTH];
            end
        end
        if (seedFound) begin
            nextAssigned[selectedVertex] = 1'b1;
            nextBaseColor[selectedVertex] = 1'b0;
            nextComponentIds[selectedVertex*INDEX_WIDTH +: INDEX_WIDTH] =
                componentCount[depth][INDEX_WIDTH-1:0];
        end

        for (inputIndex = 0; inputIndex < LANES; inputIndex = inputIndex + 1) begin
            if (inputIndex < width) begin
                for (outputIndex = 0; outputIndex < LANES; outputIndex = outputIndex + 1) begin
                    sourcePair = int'(frameSources[depth][outputIndex*INDEX_WIDTH +: INDEX_WIDTH]) >> 1;
                    if (outputIndex < width && frameValid[depth][outputIndex] &&
                        sourcePair == (inputIndex >> 1) &&
                        actualColor[outputIndex] == ((inputIndex & 1) != 0))
                        firstControls[inputIndex] = frameSources[depth][outputIndex*INDEX_WIDTH];
                end
            end
        end
        for (pairIndex = 0; pairIndex < LANES / 2; pairIndex = pairIndex + 1) begin
            if (pairIndex < subnetWidth) begin
                upperValid[pairIndex] =
                    (frameValid[depth][2*pairIndex] && !actualColor[2*pairIndex]) ||
                    (frameValid[depth][2*pairIndex+1] && !actualColor[2*pairIndex+1]);
                lowerValid[pairIndex] =
                    (frameValid[depth][2*pairIndex] && actualColor[2*pairIndex]) ||
                    (frameValid[depth][2*pairIndex+1] && actualColor[2*pairIndex+1]);
                if (frameValid[depth][2*pairIndex] && !actualColor[2*pairIndex])
                    upperSources[pairIndex*INDEX_WIDTH +: INDEX_WIDTH] =
                        frameSources[depth][2*pairIndex*INDEX_WIDTH +: INDEX_WIDTH] >> 1;
                else if (frameValid[depth][2*pairIndex+1] && !actualColor[2*pairIndex+1])
                    upperSources[pairIndex*INDEX_WIDTH +: INDEX_WIDTH] =
                        frameSources[depth][(2*pairIndex+1)*INDEX_WIDTH +: INDEX_WIDTH] >> 1;
                if (frameValid[depth][2*pairIndex] && actualColor[2*pairIndex])
                    lowerSources[pairIndex*INDEX_WIDTH +: INDEX_WIDTH] =
                        frameSources[depth][2*pairIndex*INDEX_WIDTH +: INDEX_WIDTH] >> 1;
                else if (frameValid[depth][2*pairIndex+1] && actualColor[2*pairIndex+1])
                    lowerSources[pairIndex*INDEX_WIDTH +: INDEX_WIDTH] =
                        frameSources[depth][(2*pairIndex+1)*INDEX_WIDTH +: INDEX_WIDTH] >> 1;
            end
        end
        nextFlip = componentFlip[depth] + {{(LANES-2){1'b0}}, 2'b10};
        hasNextOrientation = componentCount[depth] > 1 && nextFlip != '0 &&
            (nextFlip >> componentCount[depth]) == '0;
    end

    always_ff @(posedge clk or posedge reset) begin
        if (reset) begin
            state <= IDLE;
            depth <= '0;
            controls <= '0;
            routable <= 1'b0;
            workingControls <= '0;
            searchCycles <= '0;
            backtrackCount <= '0;
        end else begin
            if (state != IDLE && state != OUTPUT_RESULT)
                searchCycles <= searchCycles + 1'b1;
            if (state == RETRY_ORIENTATION &&
                (speculativeOrientation[depth] || hasNextOrientation))
                backtrackCount <= backtrackCount + 1'b1;
            case (state)
                IDLE: begin
                    if (startValid && startReady) begin
                        frameValid[0] <= routeValid;
                        frameSources[0] <= sourceIndices;
                        frameBase[0] <= '0;
                        workingControls <= '0;
                        depth <= '0;
                        state <= INITIALIZE;
                    end
                end
                INITIALIZE: begin
                    assigned[depth] <= '0;
                    expanded[depth] <= '0;
                    baseColor[depth] <= '0;
                    componentCount[depth] <= '0;
                    componentFlip[depth] <= '0;
                    waitingLower[depth] <= 1'b0;
                    speculativeOrientation[depth] <= 1'b0;
                    componentIds[depth] <= '0;
                    if (!(|frameValid[depth])) begin
                        state <= RETURN_SUCCESS;
                    end else if (invalidSource) begin
                        state <= RETURN_FAILURE;
                    end else if (width == 4) begin
                        for (sequentialIndex = 0; sequentialIndex < 12; sequentialIndex = sequentialIndex + 1)
                            workingControls[int'(frameBase[depth])+sequentialIndex] <= base4Controls[sequentialIndex];
                        state <= base4Legal ? RETURN_SUCCESS : RETURN_FAILURE;
                    end else if (width == 2) begin
                        workingControls[frameBase[depth]] <=
                            frameValid[depth][0] && frameSources[depth][0];
                        workingControls[frameBase[depth]+1] <=
                            frameValid[depth][1] && frameSources[depth][INDEX_WIDTH];
                        state <= RETURN_SUCCESS;
                    end else if (frameSingleSource) begin
                        workingControls <= (workingControls & ~(frameControlMask << frameBase[depth])) |
                            (frameBroadcastControls << frameBase[depth]);
                        state <= RETURN_SUCCESS;
                    end else if (fixedOrientationLegal) begin
                        baseColor[depth] <= FIXED_COLOR;
                        speculativeOrientation[depth] <= 1'b1;
                        state <= BUILD_CHILDREN;
                    end else begin
                        state <= COLOR_GRAPH;
                    end
                end
                COLOR_GRAPH: begin
                    if (graphConflict) begin
                        state <= RETURN_FAILURE;
                    end else if (frontierFound) begin
                        expanded[depth][selectedVertex] <= 1'b1;
                        assigned[depth] <= nextAssigned;
                        baseColor[depth] <= nextBaseColor;
                        componentIds[depth] <= nextComponentIds;
                    end else if (seedFound) begin
                        assigned[depth] <= nextAssigned;
                        baseColor[depth] <= nextBaseColor;
                        componentIds[depth] <= nextComponentIds;
                        expanded[depth][selectedVertex] <= 1'b1;
                        componentCount[depth] <= componentCount[depth] + 1'b1;
                    end else begin
                        state <= BUILD_CHILDREN;
                    end
                end
                BUILD_CHILDREN: begin
                    for (sequentialIndex = 0; sequentialIndex < LANES; sequentialIndex = sequentialIndex + 1) begin
                        if (sequentialIndex < width) begin
                            workingControls[int'(frameBase[depth])+sequentialIndex] <= firstControls[sequentialIndex];
                            workingControls[int'(frameBase[depth])+width+2*subnetControlWidth+sequentialIndex] <=
                                actualColor[sequentialIndex];
                        end
                    end
                    savedLowerValid[depth] <= lowerValid;
                    savedLowerSources[depth] <= lowerSources;
                    waitingLower[depth] <= 1'b0;
                    frameValid[depth+1] <= upperValid;
                    frameSources[depth+1] <= upperSources;
                    frameBase[depth+1] <= BASE_WIDTH'(int'(frameBase[depth]) + width);
                    depth <= depth + 1'b1;
                    state <= INITIALIZE;
                end
                PUSH_LOWER: begin
                    waitingLower[depth] <= 1'b1;
                    frameValid[depth+1] <= savedLowerValid[depth];
                    frameSources[depth+1] <= savedLowerSources[depth];
                    frameBase[depth+1] <= BASE_WIDTH'(int'(frameBase[depth]) + width + subnetControlWidth);
                    depth <= depth + 1'b1;
                    state <= INITIALIZE;
                end
                RETRY_ORIENTATION: begin
                    if (speculativeOrientation[depth]) begin
                        speculativeOrientation[depth] <= 1'b0;
                        baseColor[depth] <= '0;
                        state <= COLOR_GRAPH;
                    end else if (hasNextOrientation) begin
                        componentFlip[depth] <= nextFlip;
                        state <= BUILD_CHILDREN;
                    end else begin
                        state <= RETURN_FAILURE;
                    end
                end
                RETURN_SUCCESS: begin
                    if (depth == 0) begin
                        controls <= workingControls;
                        routable <= 1'b1;
                        state <= OUTPUT_RESULT;
                    end else begin
                        depth <= depth - 1'b1;
                        if (waitingLower[depth-1])
                            state <= RETURN_SUCCESS;
                        else
                            state <= PUSH_LOWER;
                    end
                end
                RETURN_FAILURE: begin
                    if (depth == 0) begin
                        controls <= '0;
                        routable <= 1'b0;
                        state <= OUTPUT_RESULT;
                    end else begin
                        depth <= depth - 1'b1;
                        state <= RETRY_ORIENTATION;
                    end
                end
                OUTPUT_RESULT: begin
                    if (resultReady)
                        state <= IDLE;
                end
                default: state <= IDLE;
            endcase
        end
    end
endmodule
