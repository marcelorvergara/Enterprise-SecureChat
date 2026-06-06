import { Component, OnInit, inject } from '@angular/core';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CommonModule, DatePipe, SlicePipe } from '@angular/common';
import {
  AdminService,
  RoleWithRestrictions,
  AuditLogEntry,
} from '../../core/services/admin.service';

interface RestrictionRow {
  id: string;
  roleName: string;
  subjectPath: string;
  reason?: string;
  createdAt: string;
}

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    DatePipe,
    SlicePipe,
    CommonModule,
  ],
  templateUrl: './admin.component.html',
  styleUrls: ['./admin.component.css'],
})
export class AdminComponent implements OnInit {
  private readonly adminService = inject(AdminService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly fb = inject(FormBuilder);

  // ── Restriction table ────────────────────────────────────────────────────
  roles: RoleWithRestrictions[] = [];
  restrictionDataSource = new MatTableDataSource<RestrictionRow>();
  restrictionColumns = ['roleName', 'subjectPath', 'reason', 'createdAt', 'actions'];
  availableRoles: string[] = [];
  loadingRoles = false;

  // ── Audit log table ──────────────────────────────────────────────────────
  auditDataSource = new MatTableDataSource<AuditLogEntry>();
  auditColumns = ['accessedAt', 'userSub', 'roleNames', 'restrictedPaths', 'queryHash'];
  auditTotalElements = 0;
  auditPageSize = 20;
  loadingAudit = false;

  // ── Add restriction form ─────────────────────────────────────────────────
  addForm!: FormGroup;
  submitting = false;
  confirmDeleteId: string | null = null;

  ngOnInit(): void {
    this.addForm = this.fb.group({
      roleName: ['', Validators.required],
      subjectPath: ['', [Validators.required, Validators.pattern(/^[a-z0-9][a-z0-9/_-]*$/)]],
      reason: [''],
    });
    this.loadRoles();
    this.loadAuditLog(0, this.auditPageSize);
  }

  loadRoles(): void {
    this.loadingRoles = true;
    this.adminService.getRoles().subscribe({
      next: roles => {
        this.roles = roles;
        this.availableRoles = roles.map(r => r.roleName);
        this.restrictionDataSource.data = roles.flatMap(role =>
          role.restrictions.map(r => ({
            id: r.id,
            roleName: role.roleName,
            subjectPath: r.subjectPath,
            reason: r.reason,
            createdAt: r.createdAt,
          }))
        );
        this.loadingRoles = false;
      },
      error: () => {
        this.snackBar.open('Failed to load roles.', 'Dismiss', { duration: 4000 });
        this.loadingRoles = false;
      },
    });
  }

  addRestriction(): void {
    if (this.addForm.invalid || this.submitting) return;
    this.submitting = true;
    const { roleName, subjectPath, reason } = this.addForm.value;
    this.adminService.addRestriction(roleName, { subjectPath, reason: reason || undefined }).subscribe({
      next: () => {
        this.snackBar.open(`Restriction added: ${roleName} → ${subjectPath}`, 'OK', { duration: 3000 });
        this.addForm.patchValue({ subjectPath: '', reason: '' });
        this.addForm.markAsPristine();
        this.loadRoles();
        this.submitting = false;
      },
      error: err => {
        const msg = err.status === 409 ? 'That restriction already exists.' : 'Failed to add restriction.';
        this.snackBar.open(msg, 'Dismiss', { duration: 4000 });
        this.submitting = false;
      },
    });
  }

  requestDelete(row: RestrictionRow): void {
    this.confirmDeleteId = row.id;
  }

  confirmDelete(row: RestrictionRow): void {
    this.confirmDeleteId = null;
    this.adminService.removeRestriction(row.roleName, row.subjectPath).subscribe({
      next: () => {
        this.snackBar.open(`Removed: ${row.roleName} → ${row.subjectPath}`, 'OK', { duration: 3000 });
        this.loadRoles();
      },
      error: () => this.snackBar.open('Failed to remove restriction.', 'Dismiss', { duration: 4000 }),
    });
  }

  cancelDelete(): void {
    this.confirmDeleteId = null;
  }

  loadAuditLog(page: number, size: number): void {
    this.loadingAudit = true;
    this.adminService.getAuditLog(page, size).subscribe({
      next: result => {
        this.auditDataSource.data = result.content;
        this.auditTotalElements = result.totalElements;
        this.loadingAudit = false;
      },
      error: () => {
        this.snackBar.open('Failed to load audit log.', 'Dismiss', { duration: 4000 });
        this.loadingAudit = false;
      },
    });
  }

  onAuditPage(event: PageEvent): void {
    this.auditPageSize = event.pageSize;
    this.loadAuditLog(event.pageIndex, event.pageSize);
  }

  get totalRestrictions(): number {
    return this.restrictionDataSource.data.length;
  }

  get rolesAffectedCount(): number {
    return new Set(this.restrictionDataSource.data.map(r => r.roleName)).size;
  }
}
