// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Routes } from '@angular/router';
import { signedInGuard } from './core/session';
import { Agenda } from './pages/agenda';
import { ConversationPage, Conversations } from './pages/conversations';
import { DecisionsPage } from './pages/decisions';
import { Login } from './pages/login';
import { Report } from './pages/report';
import { Shell } from './pages/shell';

export const routes: Routes = [
  { path: 'login', component: Login, title: 'Sign in · Turno' },
  {
    path: '',
    component: Shell,
    canActivate: [signedInGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'report' },
      { path: 'report', component: Report, title: 'Daily report · Turno' },
      { path: 'agenda', component: Agenda, title: 'Agenda · Turno' },
      { path: 'decisions', component: DecisionsPage, title: 'Decisions · Turno' },
      { path: 'conversations', component: Conversations, title: 'Conversations · Turno' },
      { path: 'conversations/:id', component: ConversationPage, title: 'Conversation · Turno' },
    ],
  },
  { path: '**', redirectTo: '' },
];
