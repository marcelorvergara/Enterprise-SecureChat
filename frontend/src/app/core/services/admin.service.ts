import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface RestrictionView {
  id: string;
  subjectPath: string;
  reason?: string;
  createdAt: string;
}

export interface RoleWithRestrictions {
  roleName: string;
  restrictions: RestrictionView[];
}

export interface AddRestrictionRequest {
  subjectPath: string;
  reason?: string;
}

export interface AuditLogEntry {
  id: string;
  userSub: string;
  roleNames: string[];
  restrictedPaths: string[];
  queryHash: string;
  accessedAt: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  getRoles(): Observable<RoleWithRestrictions[]> {
    return this.http.get<RoleWithRestrictions[]>('/api/admin/roles');
  }

  addRestriction(role: string, request: AddRestrictionRequest): Observable<RestrictionView> {
    return this.http.post<RestrictionView>(`/api/admin/roles/${role}/restrictions`, request);
  }

  removeRestriction(role: string, subjectPath: string): Observable<void> {
    const params = new HttpParams().set('subjectPath', subjectPath);
    return this.http.delete<void>(`/api/admin/roles/${role}/restrictions`, { params });
  }

  getAuditLog(page: number, size: number): Observable<PageResponse<AuditLogEntry>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<AuditLogEntry>>('/api/admin/audit-log', { params });
  }
}
