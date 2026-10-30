module TrapezoidBenesRouteControl #(
    parameter integer LANES = 128,
    parameter integer INDEX_WIDTH = 7,
    parameter integer STAGES = 13,
    parameter integer CONTROL_WIDTH = 1664
) (
    input  logic clk,
    input  logic reset,
    input  logic startValid,
    output logic startReady,
    output logic resultValid,
    input  logic resultReady,
    input  logic [LANES-1:0] routeValid,
    input  logic [LANES*INDEX_WIDTH-1:0] sourceIndices,
    output logic [CONTROL_WIDTH-1:0] controls,
    output logic routable
);

    logic [INDEX_WIDTH-1:0] routeSource [0:LANES-1];
    logic routeIsValid [0:LANES-1];

    task automatic solveSubnet;
        input integer width;
        input integer controlBase;
        input logic validIn [0:LANES-1];
        input logic [INDEX_WIDTH-1:0] sourceIn [0:LANES-1];
        output logic [CONTROL_WIDTH-1:0] controlsOut;
        output logic legalOut;

        logic assigned [0:LANES-1];
        logic color [0:LANES-1];
        logic upperValid [0:LANES-1];
        logic lowerValid [0:LANES-1];
        logic [INDEX_WIDTH-1:0] upperSource [0:LANES-1];
        logic [INDEX_WIDTH-1:0] lowerSource [0:LANES-1];
        logic [CONTROL_WIDTH-1:0] upperControls;
        logic [CONTROL_WIDTH-1:0] lowerControls;
        logic upperLegal;
        logic lowerLegal;
        logic hasNeighbour;
        logic seedFound;
        logic desiredColor;
        integer i;
        integer j;
        integer stage;
        integer pairIndex;
        integer subnetWidth;
        integer subnetControlWidth;
        integer sourcePair;
        integer otherPair;
        integer sourceSide;
        integer otherSide;

        begin
            controlsOut = '0;
            legalOut = 1'b1;
            for (i = 0; i < LANES; i = i + 1) begin
                assigned[i] = 1'b0;
                color[i] = 1'b0;
                upperValid[i] = 1'b0;
                lowerValid[i] = 1'b0;
                upperSource[i] = '0;
                lowerSource[i] = '0;
            end
            upperControls = '0;
            lowerControls = '0;

            if (width == 1) begin
                if (validIn[0] && sourceIn[0] != '0)
                    legalOut = 1'b0;
            end else if (width == 2) begin
                for (i = 0; i < 2; i = i + 1) begin
                    if (validIn[i] && sourceIn[i] > 1)
                        legalOut = 1'b0;
                    controlsOut[controlBase+i] =
                        validIn[i] ? sourceIn[i][0] : 1'b0;
                end
            end else begin
                // Color each connected component of the Benes route graph.
                // Edges connect an output pair with different sources and
                // two requests for opposite sides of the same input pair.
                for (stage = 0; stage < width; stage = stage + 1) begin
                    seedFound = 1'b0;
                    for (i = 0; i < width; i = i + 1) begin
                        if (!seedFound && !assigned[i] && validIn[i]) begin
                            assigned[i] = 1'b1;
                            color[i] = 1'b0;
                            seedFound = 1'b1;
                        end
                    end
                    for (j = 0; j < width; j = j + 1) begin
                        if (!assigned[j] && validIn[j]) begin
                            hasNeighbour = 1'b0;
                            desiredColor = 1'b0;
                            for (pairIndex = 0; pairIndex < width;
                                 pairIndex = pairIndex + 1) begin
                                if (assigned[pairIndex] && validIn[pairIndex]) begin
                                    sourcePair = sourceIn[j] >> 1;
                                    otherPair = sourceIn[pairIndex] >> 1;
                                    sourceSide = sourceIn[j] & 1;
                                    otherSide = sourceIn[pairIndex] & 1;
                                    if (((pairIndex == (j ^ 1)) &&
                                         (sourceIn[j] != sourceIn[pairIndex])) ||
                                        ((sourcePair == otherPair) &&
                                         (sourceSide != otherSide))) begin
                                        if (!hasNeighbour) begin
                                            desiredColor = ~color[pairIndex];
                                            hasNeighbour = 1'b1;
                                        end else if (desiredColor == color[pairIndex]) begin
                                            legalOut = 1'b0;
                                        end
                                    end
                                end
                            end
                            if (hasNeighbour) begin
                                assigned[j] = 1'b1;
                                color[j] = desiredColor;
                            end
                        end
                    end
                end

                for (i = 0; i < width; i = i + 1) begin
                    if (validIn[i] && !assigned[i])
                        legalOut = 1'b0;
                    if (validIn[i]) begin
                        for (j = i + 1; j < width; j = j + 1) begin
                            if (validIn[j]) begin
                                if ((j == (i ^ 1)) &&
                                    (sourceIn[i] != sourceIn[j]) &&
                                    (color[i] == color[j]))
                                    legalOut = 1'b0;
                                if (((sourceIn[i] >> 1) == (sourceIn[j] >> 1)) &&
                                    ((sourceIn[i] & 1) != (sourceIn[j] & 1)) &&
                                    (color[i] == color[j]))
                                    legalOut = 1'b0;
                            end
                        end
                    end
                end

                subnetWidth = width / 2;
                subnetControlWidth = subnetWidth * (2 * $clog2(subnetWidth) - 1);

                for (i = 0; i < width; i = i + 1) begin
                    pairIndex = i / 2;
                    if (i[0] == 1'b0) begin
                        controlsOut[controlBase+i] = 1'b0;
                        for (j = 0; j < width; j = j + 1) begin
                            if (validIn[j] && !color[j] &&
                                ((sourceIn[j] >> 1) == pairIndex))
                                controlsOut[controlBase+i] = sourceIn[j][0];
                        end
                    end else begin
                        controlsOut[controlBase+i] = 1'b0;
                        for (j = 0; j < width; j = j + 1) begin
                            if (validIn[j] && color[j] &&
                                ((sourceIn[j] >> 1) == pairIndex))
                                controlsOut[controlBase+i] = sourceIn[j][0];
                        end
                    end
                end

                for (i = 0; i < subnetWidth; i = i + 1) begin
                    upperValid[i] = (validIn[2*i] && !color[2*i]) ||
                                    (validIn[2*i+1] && !color[2*i+1]);
                    lowerValid[i] = (validIn[2*i] && color[2*i]) ||
                                    (validIn[2*i+1] && color[2*i+1]);
                    if (validIn[2*i] && !color[2*i])
                        upperSource[i] = sourceIn[2*i] >> 1;
                    else if (validIn[2*i+1] && !color[2*i+1])
                        upperSource[i] = sourceIn[2*i+1] >> 1;
                    if (validIn[2*i] && color[2*i])
                        lowerSource[i] = sourceIn[2*i] >> 1;
                    else if (validIn[2*i+1] && color[2*i+1])
                        lowerSource[i] = sourceIn[2*i+1] >> 1;
                end

                solveSubnet(subnetWidth, controlBase + width,
                            upperValid, upperSource,
                            upperControls, upperLegal);
                solveSubnet(subnetWidth,
                            controlBase + width + subnetControlWidth,
                            lowerValid, lowerSource,
                            lowerControls, lowerLegal);
                legalOut = legalOut && upperLegal && lowerLegal;
                controlsOut = controlsOut | upperControls | lowerControls;
                for (i = 0; i < width; i = i + 1)
                    controlsOut[controlBase + width + 2*subnetControlWidth + i] = color[i];
            end
        end
    endtask

    integer lane;
    logic [CONTROL_WIDTH-1:0] solvedControls;
    logic solvedLegal;
    logic [CONTROL_WIDTH-1:0] preparedControls;
    logic preparedLegal;
    logic [CONTROL_WIDTH-1:0] stageControls [0:6];
    logic stageLegal [0:6];
    logic stageValid [0:6];
    logic stageReady [0:6];
    integer readyIndex;
    integer seqIndex;

    always_comb begin
        for (lane = 0; lane < LANES; lane = lane + 1) begin
            routeIsValid[lane] = routeValid[lane];
            routeSource[lane] = sourceIndices[lane*INDEX_WIDTH +: INDEX_WIDTH];
        end
        solveSubnet(LANES, 0, routeIsValid, routeSource,
                    solvedControls, solvedLegal);
        preparedControls = solvedControls;
        preparedLegal = solvedLegal && (|routeValid);
    end

    always_comb begin
        stageReady[6] = !stageValid[6] || resultReady;
        for (readyIndex = 5; readyIndex >= 0; readyIndex = readyIndex - 1)
            stageReady[readyIndex] = !stageValid[readyIndex] ||
                stageReady[readyIndex + 1];
        startReady = stageReady[0];
        resultValid = stageValid[6];
        controls = stageControls[6];
        routable = stageLegal[6];
    end

    // The route solver is a clock-domain endpoint, so reset must clear both
    // the valid bits and the metadata registers before the first command can
    // reach the PE-row control FIFO.  Asynchronous assertion also covers the
    // short reset pulse used by the Verilator clock-domain stimulus.
    always_ff @(posedge clk or posedge reset) begin
        if (reset) begin
            for (seqIndex = 0; seqIndex < 7; seqIndex = seqIndex + 1) begin
                stageValid[seqIndex] <= 1'b0;
                stageLegal[seqIndex] <= 1'b0;
                stageControls[seqIndex] <= '0;
            end
        end else begin
            for (seqIndex = 0; seqIndex < 7; seqIndex = seqIndex + 1) begin
                if (stageReady[seqIndex]) begin
                    if (seqIndex == 0) begin
                        stageValid[seqIndex] <= startValid;
                        if (startValid) begin
                            stageControls[seqIndex] <= preparedControls;
                            stageLegal[seqIndex] <= preparedLegal;
                        end
                    end else begin
                        stageValid[seqIndex] <= stageValid[seqIndex - 1];
                        if (stageValid[seqIndex - 1]) begin
                            stageControls[seqIndex] <= stageControls[seqIndex - 1];
                            stageLegal[seqIndex] <= stageLegal[seqIndex - 1];
                        end
                    end
                end
            end
        end
    end
endmodule
