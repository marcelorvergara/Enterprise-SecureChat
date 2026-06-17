import { Component, OnInit, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ChatService, SourceCitation, SourcePreview } from '../../core/services/chat.service';

export interface SourcePreviewDialogData {
  conversationId: string;
  citation: SourceCitation;
}

@Component({
  selector: 'app-source-preview-dialog',
  standalone: true,
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
  template: `
    <h2 mat-dialog-title class="preview-title">
      <mat-icon class="preview-title-icon">description</mat-icon>
      Source Preview
    </h2>

    <mat-dialog-content class="preview-content">
      @if (loading) {
        <div class="preview-loading">
          <mat-spinner diameter="32"></mat-spinner>
          <span>Loading chunk…</span>
        </div>
      } @else if (forbidden) {
        <div class="preview-error">
          <mat-icon>lock</mat-icon>
          <span>Access restricted — you do not have permission to view this source.</span>
        </div>
      } @else if (preview) {
        <div class="preview-meta">
          <mat-chip-set>
            <mat-chip class="meta-chip">
              <mat-icon matChipAvatar>insert_drive_file</mat-icon>
              {{ preview.sourceFile }}
            </mat-chip>
            <mat-chip class="meta-chip">
              <mat-icon matChipAvatar>folder</mat-icon>
              {{ preview.subjectPath }}
            </mat-chip>
            @if (preview.pageNumber) {
              <mat-chip class="meta-chip">
                <mat-icon matChipAvatar>book</mat-icon>
                Page {{ preview.pageNumber }}
              </mat-chip>
            }
            @if (preview.sheetName) {
              <mat-chip class="meta-chip">
                <mat-icon matChipAvatar>table_chart</mat-icon>
                {{ preview.sheetName }}
              </mat-chip>
            }
          </mat-chip-set>
        </div>
        <div class="preview-divider"></div>
        <pre class="preview-chunk-text">{{ preview.chunkText }}</pre>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      @if (preview) {
        <button mat-button (click)="copyFilename()">
          <mat-icon>content_copy</mat-icon>
          Copy filename
        </button>
      }
      <button mat-button mat-dialog-close cdkFocusInitial>Close</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .preview-title { display: flex; align-items: center; gap: 8px; }
    .preview-title-icon { font-size: 20px; width: 20px; height: 20px; }
    .preview-content { min-height: 120px; padding-top: 8px; }
    .preview-loading { display: flex; align-items: center; gap: 12px; padding: 24px 0; color: var(--c-text-muted, #aaa); }
    .preview-error { display: flex; align-items: center; gap: 10px; padding: 24px 0; color: #f59e0b; }
    .preview-meta { margin-bottom: 12px; overflow: hidden; }
    .meta-chip { font-size: 0.78rem; max-width: 100%; }
    .preview-divider { height: 1px; background: var(--c-border, rgba(255,255,255,0.1)); margin: 8px 0 12px; }
    .preview-chunk-text {
      white-space: pre-wrap;
      word-break: break-word;
      font-family: inherit;
      font-size: 0.88rem;
      line-height: 1.6;
      margin: 0;
      max-height: 360px;
      overflow-y: auto;
      padding: 12px;
      border-radius: 6px;
      background: var(--c-input-bg, rgba(255,255,255,0.05));
    }
  `],
})
export class SourcePreviewDialogComponent implements OnInit {
  private readonly chatService = inject(ChatService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject(MatDialogRef<SourcePreviewDialogComponent>);
  readonly data = inject<SourcePreviewDialogData>(MAT_DIALOG_DATA);

  loading = true;
  forbidden = false;
  preview: SourcePreview | null = null;

  ngOnInit(): void {
    this.chatService
      .getSourcePreview(this.data.conversationId, this.data.citation.chunkId)
      .subscribe({
        next: p => {
          this.preview = p;
          this.loading = false;
        },
        error: err => {
          this.loading = false;
          if (err.status === 403) {
            this.forbidden = true;
          } else {
            this.dialogRef.close();
            this.snackBar.open('Failed to load source preview', undefined, { duration: 3000 });
          }
        },
      });
  }

  copyFilename(): void {
    if (!this.preview) return;
    navigator.clipboard.writeText(this.preview.sourceFile).then(() => {
      this.snackBar.open('Filename copied to clipboard', undefined, {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom',
      });
    });
  }
}
