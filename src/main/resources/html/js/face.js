const SVG_NS = "http://www.w3.org/2000/svg";

const WIDTH = 2560;
const HEIGHT = 1600;

const EYE_RADIUS = 160;
const EYE_BORDER_WIDTH = 4;
const PUPIL_RADIUS = 98;
const HIGHLIGHT_RADIUS = 54;
const HIGHLIGHT_OFFSET_X = 54;
const HIGHLIGHT_OFFSET_Y = -73;
const CLOSED_EYE_STROKE_WIDTH = 22;

const MOUTH_CENTER_X = 1224;
const MOUTH_CENTER_Y = 1047;
const MOUTH_SIZE_SCALE = 1.2;
const MOUTH_STROKE_WIDTH = 18 * 1.2;

const EYES = [
    { cx: 720, cy: 653 },
    { cx: 1747, cy: 653 },
];

const OPEN_COLORS = {
    border: "#20232a",
    iris: "#c04078",
    pupil: "#740d38",
    highlight: "#ffffff",
    mouth: "#000000",
};

const CLOSED_COLORS = {
    border: "#6b7078",
    iris: "#aeb4bc",
    pupil: "#818892",
    highlight: "#e5e7eb",
    mouth: "#818892",
};

export function drawFace(svg) {
    svg.setAttribute("viewBox", `0 0 ${WIDTH} ${HEIGHT}`);
    svg.replaceChildren();

    const title = element("title", { id: "face-title" });
    title.textContent = "AIの表情";
    svg.append(title);

    const openEyesGroup = element("g", { class: "face-awake-parts face-eye-open-parts" });
    appendEyes(openEyesGroup, OPEN_COLORS, 1);
    svg.append(openEyesGroup);

    const eyes60Group = element("g", { class: "face-eye-60-parts" });
    appendEyes(eyes60Group, OPEN_COLORS, 0.6);
    svg.append(eyes60Group);

    const eyes30Group = element("g", { class: "face-eye-30-parts" });
    appendEyes(eyes30Group, OPEN_COLORS, 0.3);
    svg.append(eyes30Group);

    const closedEyesGroup = element("g", { class: "face-closed-parts face-eye-closed-parts" });
    appendClosedEyes(closedEyesGroup, CLOSED_COLORS);
    svg.append(closedEyesGroup);

    const mouthGroup = element("g", { class: "face-mouth-parts" });
    appendMouth(mouthGroup, OPEN_COLORS.mouth, true);
    appendSmileMouth(mouthGroup, OPEN_COLORS.mouth);
    appendMouth(mouthGroup, CLOSED_COLORS.mouth, false);
    svg.append(mouthGroup);

    return {
        mouth: svg.querySelector("#mouth"),
        smileMouth: svg.querySelector("#mouth-smile"),
    };
}

function appendEyes(group, colors, heightRatio) {
    for (const eye of EYES) {
        appendEye(group, eye.cx, eye.cy, colors, heightRatio);
    }
}

function appendEye(group, cx, cy, colors, heightRatio) {
    group.append(
            ellipse(cx, cy, EYE_RADIUS + EYE_BORDER_WIDTH, (EYE_RADIUS + EYE_BORDER_WIDTH) * heightRatio, colors.border),
            ellipse(cx, cy, EYE_RADIUS, EYE_RADIUS * heightRatio, colors.iris),
            ellipse(cx, cy, PUPIL_RADIUS, PUPIL_RADIUS * heightRatio, colors.pupil),
            ellipse(cx + HIGHLIGHT_OFFSET_X, cy + HIGHLIGHT_OFFSET_Y * heightRatio,
                    HIGHLIGHT_RADIUS, HIGHLIGHT_RADIUS * heightRatio, colors.highlight));
}

function appendClosedEyes(group, colors) {
    for (const eye of EYES) {
        group.append(element("line", {
            x1: eye.cx - EYE_RADIUS,
            y1: eye.cy,
            x2: eye.cx + EYE_RADIUS,
            y2: eye.cy,
            stroke: colors.pupil,
            "stroke-width": CLOSED_EYE_STROKE_WIDTH,
            "stroke-linecap": "round",
        }));
    }
}

function appendMouth(group, stroke, activeMouth) {
    const left = scaled(1081, 1006);
    const leftControl = scaled(1152, 1088);
    const centerTop = scaled(1224, 1006);
    const rightControl = scaled(1296, 1088);
    const right = scaled(1368, 1006);

    group.append(element("path", {
        id: activeMouth ? "mouth" : null,
        class: activeMouth ? "mouth" : "mouth-closed",
        d: `M ${left} Q ${leftControl} ${centerTop} Q ${rightControl} ${right}`,
        fill: "none",
        stroke,
        "stroke-width": MOUTH_STROKE_WIDTH,
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
    }));
}

function appendSmileMouth(group, stroke) {
    const left = scaled(1081, 1040);
    const control = scaled(1224, 1148);
    const right = scaled(1368, 1040);

    group.append(element("path", {
        id: "mouth-smile",
        class: "mouth-smile",
        d: `M ${left} Q ${control} ${right}`,
        fill: "none",
        stroke,
        "stroke-width": MOUTH_STROKE_WIDTH,
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
    }));
}

function scaled(x, y) {
    const scaledX = MOUTH_CENTER_X + (x - MOUTH_CENTER_X) * MOUTH_SIZE_SCALE;
    const scaledY = MOUTH_CENTER_Y + (y - MOUTH_CENTER_Y) * MOUTH_SIZE_SCALE;
    return `${format(scaledX)} ${format(scaledY)}`;
}

function ellipse(cx, cy, rx, ry, fill) {
    return element("ellipse", {
        cx,
        cy,
        rx,
        ry,
        fill,
    });
}

function element(name, attributes) {
    const node = document.createElementNS(SVG_NS, name);
    for (const [key, value] of Object.entries(attributes)) {
        if (value !== null) {
            node.setAttribute(key, value);
        }
    }
    return node;
}

function format(value) {
    if (Number.isInteger(value)) {
        return String(value);
    }
    return value.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}
