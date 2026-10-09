import React from "react";
import { Composition, registerRoot } from "remotion";
import { TapSceneDemo, type RenderProps } from "./Composition";
const Root: React.FC = () => (
  <Composition
    defaultProps={{} as RenderProps}
    id="TapSceneDemo"
    component={TapSceneDemo}
    durationInFrames={1}
    fps={30}
    width={1080}
    height={1920}
    calculateMetadata={({ props }) => ({
      durationInFrames: props.plan.totalFrames,
      fps: 30,
      width: props.plan.canvas.width,
      height: props.plan.canvas.height,
    })}
  />
);
registerRoot(Root);
