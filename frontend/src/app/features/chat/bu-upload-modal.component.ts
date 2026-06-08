import { Component } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

@Component({
  selector: 'app-bu-upload-modal',
  standalone: true,
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <div class="modal-header">
      <div class="modal-icon-wrap">
        <mat-icon>cloud_upload</mat-icon>
      </div>
      <div class="modal-header-text">
        <h2 mat-dialog-title>Index BU Document</h2>
        <p class="modal-subtitle">Permanently indexed with restricted BU access</p>
      </div>
    </div>

    <mat-dialog-content>
      @if (!result) {
        <label class="drop-zone" [class.has-file]="selectedFile">
          @if (!selectedFile) {
            <mat-icon class="drop-icon">upload_file</mat-icon>
            <span class="drop-primary">Click to choose a file</span>
            <span class="drop-hint">PDF · XLSX · XLS · PNG · JPG · TIFF · TXT · CSV</span>
          } @else {
            <mat-icon class="drop-icon has-file-icon">description</mat-icon>
            <span class="drop-filename">{{ selectedFile.name }}</span>
            <span class="drop-hint">Click to change</span>
          }
          <input type="file" hidden
            accept=".pdf,.xlsx,.xls,.png,.jpg,.jpeg,.tiff,.tif,.txt,.md,.csv"
            (change)="onFileSelected($event)">
        </label>
      }

      @if (result === 'success') {
        <div class="result-state">
          <div class="result-icon-wrap success">
            <mat-icon>check_circle</mat-icon>
          </div>
          <p class="result-title">Document indexed successfully</p>
          <p class="result-desc">It is now searchable within your BU knowledge base.</p>
        </div>
      }

      @if (result === 'error') {
        <div class="result-state">
          <div class="result-icon-wrap error">
            <mat-icon>error_outline</mat-icon>
          </div>
          <p class="result-title">Indexing failed</p>
          <p class="result-desc">Please try again or contact your administrator.</p>
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      @if (!result) {
        <button mat-button (click)="close()">Cancel</button>
        <button class="primary-btn" mat-flat-button
          [disabled]="!selectedFile || uploading"
          (click)="upload()">
          @if (uploading) {
            <mat-spinner diameter="16" class="btn-spinner"></mat-spinner>
          }
          {{ uploading ? 'Indexing…' : 'Index Document' }}
        </button>
      } @else {
        <button class="primary-btn" mat-flat-button (click)="close()">Done</button>
      }
    </mat-dialog-actions>
  `,
  styles: [`
    /* ── Layout ── */
    :host { display: block; min-width: 380px; max-width: 480px; }

    /* ── Header ── */
    .modal-header {
      display: flex; align-items: center; gap: 14px;
      padding: 24px 24px 0;
    }
    .modal-icon-wrap {
      flex-shrink: 0;
      width: 44px; height: 44px;
      border-radius: 12px;
      background: var(--c-accent-bg);
      border: 1px solid var(--c-accent-border);
      display: flex; align-items: center; justify-content: center;
    }
    .modal-icon-wrap mat-icon { color: var(--c-accent); font-size: 22px; }
    .modal-header-text { display: flex; flex-direction: column; gap: 2px; }
    .modal-header-text h2[mat-dialog-title] {
      margin: 0; padding: 0;
      font-size: 1rem; font-weight: 600;
      line-height: 1.3; color: var(--c-text);
    }
    .modal-subtitle {
      margin: 0; font-size: 0.78rem;
      color: var(--c-text-3); line-height: 1.4;
    }

    /* ── Drop zone ── */
    mat-dialog-content { padding: 16px 24px 8px !important; }
    .drop-zone {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: 6px; cursor: pointer;
      padding: 28px 20px;
      border: 1.5px dashed var(--c-border-2);
      border-radius: 10px;
      background: var(--c-accent-ring);
      transition: border-color 0.18s, background 0.18s;
      text-align: center;
    }
    .drop-zone:hover {
      border-color: var(--c-accent);
      background: var(--c-accent-bg);
    }
    .drop-icon {
      font-size: 32px; width: 32px; height: 32px;
      color: var(--c-text-3);
      transition: color 0.18s;
    }
    .drop-zone:hover .drop-icon,
    .drop-zone.has-file .drop-icon { color: var(--c-accent); }
    .has-file-icon { color: var(--c-accent) !important; }
    .drop-primary {
      font-size: 0.88rem; font-weight: 500; color: var(--c-text-2);
    }
    .drop-filename {
      font-size: 0.88rem; font-weight: 500; color: var(--c-accent);
      max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .drop-hint {
      font-size: 0.74rem; color: var(--c-text-3); letter-spacing: 0.02em;
    }

    /* ── Result states ── */
    .result-state {
      display: flex; flex-direction: column; align-items: center;
      padding: 20px 16px; gap: 8px; text-align: center;
    }
    .result-icon-wrap {
      width: 52px; height: 52px; border-radius: 50%;
      display: flex; align-items: center; justify-content: center;
      margin-bottom: 4px;
    }
    .result-icon-wrap mat-icon { font-size: 28px; width: 28px; height: 28px; }
    .result-icon-wrap.success {
      background: var(--c-dlp-bg);
      border: 1px solid var(--c-dlp-border);
    }
    .result-icon-wrap.success mat-icon { color: var(--c-dlp-text); }
    .result-icon-wrap.error {
      background: rgba(239, 68, 68, 0.10);
      border: 1px solid rgba(239, 68, 68, 0.22);
    }
    .result-icon-wrap.error mat-icon { color: #f87171; }
    .result-title { margin: 0; font-size: 0.95rem; font-weight: 600; color: var(--c-text); }
    .result-desc  { margin: 0; font-size: 0.82rem; color: var(--c-text-3); }

    /* ── Actions ── */
    mat-dialog-actions { padding: 12px 24px 20px !important; gap: 8px; }
    .primary-btn {
      background: linear-gradient(135deg, var(--c-send-from), var(--c-send-to)) !important;
      color: #fff !important;
      border-radius: 8px !important;
      font-weight: 500 !important;
      box-shadow: 0 2px 8px var(--c-send-shadow) !important;
      display: flex; align-items: center; gap: 6px;
    }
    .primary-btn:hover:not([disabled]) {
      background: linear-gradient(135deg, var(--c-send-hover-f), var(--c-send-hover-t)) !important;
      box-shadow: 0 4px 14px var(--c-send-shadow-h) !important;
    }
    .primary-btn[disabled] {
      background: var(--c-send-dis-bg) !important;
      color: var(--c-send-dis-text) !important;
      box-shadow: none !important;
    }
    .btn-spinner { display: inline-flex; }
    ::ng-deep .btn-spinner circle { stroke: #fff !important; }
  `],
})
export class BuUploadModalComponent {
  selectedFile: File | null = null;
  uploading = false;
  result: 'success' | 'error' | null = null;

  constructor(
    private readonly dialogRef: MatDialogRef<BuUploadModalComponent>,
    private readonly http: HttpClient,
  ) {}

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
    input.value = '';
  }

  upload(): void {
    if (!this.selectedFile || this.uploading) return;
    this.uploading = true;

    const formData = new FormData();
    formData.append('file', this.selectedFile);

    this.http.post('/api/documents/ingest', formData).subscribe({
      next: () => {
        this.uploading = false;
        this.result = 'success';
      },
      error: () => {
        this.uploading = false;
        this.result = 'error';
      },
    });
  }

  close(): void {
    this.dialogRef.close(this.result === 'success');
  }
}
