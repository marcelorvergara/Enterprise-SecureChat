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
    <h2 mat-dialog-title>Index BU Document</h2>
    <mat-dialog-content class="modal-content">
      @if (!result) {
        <p class="modal-desc">
          This document will be permanently indexed into your Business Unit knowledge base
          with restricted access.
        </p>
        <label class="file-label">
          <mat-icon>upload_file</mat-icon>
          <span>{{ selectedFile ? selectedFile.name : 'Choose a file…' }}</span>
          <input type="file" hidden
            accept=".pdf,.xlsx,.xls,.png,.jpg,.jpeg,.tiff,.tif,.txt,.md,.csv"
            (change)="onFileSelected($event)">
        </label>
      }
      @if (result === 'success') {
        <div class="modal-result success">
          <mat-icon>check_circle</mat-icon>
          <span>Document indexed successfully.</span>
        </div>
      }
      @if (result === 'error') {
        <div class="modal-result error">
          <mat-icon>error</mat-icon>
          <span>Indexing failed. Please try again.</span>
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (!result) {
        <button mat-button (click)="close()">Cancel</button>
        <button mat-raised-button color="primary"
          [disabled]="!selectedFile || uploading"
          (click)="upload()">
          @if (uploading) {
            <mat-spinner diameter="18" style="display:inline-block;margin-right:6px"></mat-spinner>
          }
          {{ uploading ? 'Indexing…' : 'Index Document' }}
        </button>
      } @else {
        <button mat-button (click)="close()">Close</button>
      }
    </mat-dialog-actions>
  `,
  styles: [`
    .modal-content { min-width: 320px; padding: 8px 0; }
    .modal-desc { color: rgba(0,0,0,0.6); font-size: 0.9rem; margin: 0 0 16px; }
    .file-label {
      display: flex; align-items: center; gap: 10px; cursor: pointer;
      padding: 12px 16px; border: 1px dashed rgba(0,0,0,0.25); border-radius: 6px;
      color: rgba(0,0,0,0.6); font-size: 0.9rem;
    }
    .file-label:hover { border-color: #5c6bc0; color: #5c6bc0; }
    .modal-result { display: flex; align-items: center; gap: 10px; padding: 8px 0; font-size: 0.95rem; }
    .success { color: #2e7d32; }
    .error   { color: #c62828; }
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
