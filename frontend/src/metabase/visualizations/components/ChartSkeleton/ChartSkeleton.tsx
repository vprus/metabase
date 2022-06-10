import React, { HTMLAttributes } from "react";
import AreaSkeleton from "./AreaSkeleton";
import BarSkeleton from "./BarSkeleton";
import FunnelSkeleton from "./FunnelSkeleton";
import LineSkeleton from "./LineSkeleton";
import PieSkeleton from "./PieSkeleton";
import RowSkeleton from "./RowSkeleton";
import ScatterSkeleton from "./ScatterSkeleton";
import TableSkeleton from "./TableSkeleton";
import SmartScalarSkeleton from "./SmartScalarSkeleton";
import WaterfallSkeleton from "./WaterfallSkeleton";

export interface ChartSkeletonProps extends HTMLAttributes<HTMLDivElement> {
  display?: string | null;
  displayName?: string | null;
}

const ChartSkeleton = ({
  display,
  ...props
}: ChartSkeletonProps): JSX.Element => {
  switch (display) {
    case "area":
      return <AreaSkeleton {...props} />;
    case "bar":
      return <BarSkeleton {...props} />;
    case "funnel":
      return <FunnelSkeleton {...props} />;
    case "line":
      return <LineSkeleton {...props} />;
    case "pie":
      return <PieSkeleton {...props} />;
    case "row":
      return <RowSkeleton {...props} />;
    case "scatter":
      return <ScatterSkeleton {...props} />;
    case "table":
    case "pivot":
      return <TableSkeleton {...props} />;
    case "smartscalar":
      return <SmartScalarSkeleton {...props} />;
    case "waterfall":
      return <WaterfallSkeleton {...props} />;
    default:
      return <LineSkeleton {...props} />;
  }
};

export default ChartSkeleton;
