import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';

@Component({
  standalone: true,
  imports: [MatButtonModule, MatDialogModule],
  template: `
    <h2 mat-dialog-title>Delete conversation?</h2>
    <mat-dialog-content>This cannot be undone.</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-button color="warn" [mat-dialog-close]="true" cdkFocusInitial>Delete</button>
    </mat-dialog-actions>
  `,
})
export class DeleteConversationDialogComponent {}
