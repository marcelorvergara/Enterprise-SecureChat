import { Pipe, PipeTransform, inject } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

@Pipe({ name: 'safeMarkdown', standalone: true })
export class SafeMarkdownPipe implements PipeTransform {
  private readonly sanitizer = inject(DomSanitizer);

  transform(value: string | null | undefined): SafeHtml {
    const rawHtml = marked.parse(value ?? '') as string;
    const cleanHtml = DOMPurify.sanitize(rawHtml);
    const withRedacted = cleanHtml.replace(
      /\[REDACTED\]/g,
      '<span class="dlp-redacted">[REDACTED]</span>',
    );
    return this.sanitizer.bypassSecurityTrustHtml(withRedacted);
  }
}
