import { Injectable } from '@angular/core';
import { SourceCitation } from './chat.service';

export interface ExportMessage {
  role: 'user' | 'assistant';
  content: string;
  sources: SourceCitation[];
}

@Injectable({ providedIn: 'root' })
export class ConversationExportService {

  exportMarkdown(title: string, messages: ExportMessage[]): void {
    const date = new Date().toISOString().split('T')[0];
    const lines: string[] = [
      `# ${title}`,
      `*Exported: ${date}*`,
      '',
    ];

    for (const msg of messages) {
      lines.push('---', '');
      const speaker = msg.role === 'user' ? '**You**' : '**Assistant**';
      lines.push(`${speaker}`);
      lines.push('');
      lines.push(msg.content);
      lines.push('');
    }

    const allSources = this.collectSources(messages);
    if (allSources.length > 0) {
      lines.push('---', '');
      lines.push('## Sources & References', '');
      allSources.forEach((src, i) => {
        let entry = `${i + 1}. ${src.sourceFile} · ${src.subjectPath}`;
        if (src.pageNumber) entry += ` · Page ${src.pageNumber}`;
        if (src.sheetName) entry += ` · Sheet: ${src.sheetName}`;
        lines.push(entry);
      });
    }

    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
    this.triggerDownload(blob, `${this.slugify(title)}.md`);
  }

  exportPdf(title: string, messages: ExportMessage[]): void {
    const date = new Date().toLocaleDateString('en-GB', { year: 'numeric', month: 'long', day: 'numeric' });
    const allSources = this.collectSources(messages);

    const messagesHtml = messages.map(msg => {
      const speaker = msg.role === 'user' ? 'You' : 'Assistant';
      const roleClass = msg.role === 'user' ? 'user' : 'assistant';
      const escapedContent = this.escapeHtml(msg.content)
        .replace(/\[REDACTED\]/g, '<span class="redacted">[REDACTED]</span>');
      return `
        <div class="message ${roleClass}">
          <div class="speaker">${speaker}</div>
          <div class="body">${escapedContent}</div>
        </div>`;
    }).join('');

    const sourcesHtml = allSources.length > 0
      ? `<div class="sources-section">
          <h2>Sources &amp; References</h2>
          <ol>${allSources.map(src => {
            let item = `<strong>${this.escapeHtml(src.sourceFile)}</strong> &middot; ${this.escapeHtml(src.subjectPath)}`;
            if (src.pageNumber) item += ` &middot; Page ${src.pageNumber}`;
            if (src.sheetName) item += ` &middot; Sheet: ${this.escapeHtml(src.sheetName)}`;
            return `<li>${item}</li>`;
          }).join('')}</ol>
        </div>`
      : '';

    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>${this.escapeHtml(title)}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: Georgia, serif; font-size: 11pt; line-height: 1.6; color: #111; padding: 40px; max-width: 800px; margin: 0 auto; }
    h1 { font-size: 18pt; margin-bottom: 4px; }
    .meta { color: #666; font-size: 9pt; margin-bottom: 32px; }
    .message { margin-bottom: 20px; page-break-inside: avoid; }
    .speaker { font-size: 8pt; font-weight: bold; text-transform: uppercase; letter-spacing: 0.08em; color: #888; margin-bottom: 4px; }
    .message.assistant .speaker { color: #1a5276; }
    .body { white-space: pre-wrap; word-break: break-word; }
    .redacted { background: #fef3c7; color: #92400e; border: 1px solid #fbbf24; border-radius: 3px; padding: 0 4px; font-size: 0.85em; font-weight: bold; }
    hr { border: none; border-top: 1px solid #ddd; margin: 20px 0; }
    .sources-section { margin-top: 40px; border-top: 2px solid #ddd; padding-top: 20px; }
    .sources-section h2 { font-size: 12pt; margin-bottom: 12px; }
    ol { padding-left: 20px; }
    li { margin-bottom: 6px; font-size: 9pt; }
    @media print {
      body { padding: 0; }
      .message { page-break-inside: avoid; }
    }
  </style>
</head>
<body>
  <h1>${this.escapeHtml(title)}</h1>
  <div class="meta">Exported: ${date}</div>
  ${messagesHtml}
  ${sourcesHtml}
  <script>window.print(); window.addEventListener('afterprint', () => window.close());</script>
</body>
</html>`;

    const printWindow = window.open('', '_blank');
    if (!printWindow) return;
    printWindow.document.write(html);
    printWindow.document.close();
  }

  private collectSources(messages: ExportMessage[]): SourceCitation[] {
    const seen = new Set<string>();
    const result: SourceCitation[] = [];
    for (const msg of messages) {
      for (const src of msg.sources) {
        if (!seen.has(src.sourceFile)) {
          seen.add(src.sourceFile);
          result.push(src);
        }
      }
    }
    return result;
  }

  private triggerDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  private slugify(text: string): string {
    return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'conversation';
  }

  private escapeHtml(text: string): string {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
}
