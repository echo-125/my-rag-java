import DOMPurify from 'dompurify'

const HTML_CONFIG = {
  ALLOWED_TAGS: [
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'p', 'br', 'hr',
    'ul', 'ol', 'li',
    'blockquote',
    'pre', 'code',
    'table', 'thead', 'tbody', 'tr', 'th', 'td',
    'a', 'img',
    'strong', 'em', 'del', 'sup', 'sub',
    'svg', 'g', 'path', 'circle', 'rect', 'line', 'text', 'marker',
    'defs', 'style', 'title', 'desc',
    'span',
  ],
  ALLOWED_ATTR: [
    'href', 'src', 'alt', 'title', 'class', 'id',
    'colspan', 'rowspan', 'align', 'valign',
    'fill', 'stroke', 'stroke-width', 'd', 'transform',
    'viewBox', 'xmlns', 'width', 'height',
    'x', 'y', 'x1', 'y1', 'x2', 'y2',
    'marker-end', 'data-*',
  ],
  ALLOW_DATA_ATTR: false,
}

const SVG_CONFIG = {
  ALLOWED_TAGS: [
    'svg', 'g', 'path', 'circle', 'rect', 'line', 'polyline', 'polygon',
    'text', 'tspan', 'marker', 'defs', 'clipPath', 'use',
    'style', 'title', 'desc',
  ],
  ALLOWED_ATTR: [
    'viewBox', 'xmlns', 'width', 'height', 'class', 'id',
    'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
    'd', 'transform', 'x', 'y', 'x1', 'y1', 'x2', 'y2',
    'rx', 'ry', 'cx', 'cy', 'r',
    'marker-end', 'marker-start', 'refX', 'refY', 'markerWidth', 'markerHeight',
    'points', 'opacity', 'font-size', 'font-family', 'text-anchor',
    'dominant-baseline', 'alignment-baseline',
  ],
  ALLOW_DATA_ATTR: false,
}

/** 清洗 HTML 内容（Markdown 渲染后的输出） */
export function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, HTML_CONFIG) as string
}

/** 清洗 Mermaid SVG 输出 */
export function sanitizeSvg(svg: string): string {
  return DOMPurify.sanitize(svg, SVG_CONFIG) as string
}
