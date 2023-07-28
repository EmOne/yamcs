import { ChangeDetectionStrategy, Component, HostBinding, OnDestroy } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { AuthInfo, ConnectionInfo, PreferenceStore, User } from '@yamcs/webapp-sdk';
import { BehaviorSubject, Observable, Subscription } from 'rxjs';
import { filter, map } from 'rxjs/operators';
import { AppearanceService } from '../../core/services/AppearanceService';
import { AuthService } from '../../core/services/AuthService';
import { ConfigService, SiteLink } from '../../core/services/ConfigService';
import { YamcsService } from '../../core/services/YamcsService';
import { SelectInstanceDialog } from '../../shared/dialogs/SelectInstanceDialog';


@Component({
  selector: 'app-root',
  templateUrl: './AppComponent.html',
  styleUrls: ['./AppComponent.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent implements OnDestroy {

  @HostBinding('class')
  componentCssClass: string;

  title = 'Yamcs';
  tag: string;
  authInfo: AuthInfo;
  siteLinks: SiteLink[];

  connectionInfo$: Observable<ConnectionInfo | null>;
  connected$: Observable<boolean>;
  user$: Observable<User | null>;

  showMdbItem$ = new BehaviorSubject<boolean>(false);
  sidebar$: Observable<boolean>;
  zenMode$: Observable<boolean>;

  userSubscription: Subscription;

  constructor(
    private yamcs: YamcsService,
    router: Router,
    route: ActivatedRoute,
    private authService: AuthService,
    private preferenceStore: PreferenceStore,
    private dialog: MatDialog,
    appearanceService: AppearanceService,
    configService: ConfigService,
  ) {
    this.zenMode$ = appearanceService.zenMode$;
    this.tag = configService.getTag();
    this.authInfo = configService.getAuthInfo();
    this.siteLinks = configService.getSiteLinks();
    this.connected$ = yamcs.yamcsClient.connected$;
    this.connectionInfo$ = yamcs.connectionInfo$;
    this.user$ = authService.user$;

    this.userSubscription = this.user$.subscribe(user => {
      if (user) {
        this.showMdbItem$.next(user.hasSystemPrivilege('GetMissionDatabase'));
      } else {
        this.showMdbItem$.next(false);
      }
    });

    this.sidebar$ = router.events.pipe(
      filter(evt => evt instanceof NavigationEnd),
      map(evt => {
        let child = route;
        while (child.firstChild) {
          child = child.firstChild;
        }

        if (child.snapshot.data && child.snapshot.data['hasSidebar'] === false) {
          return false;
        } else {
          return true;
        }
      }),
    );
  }

  openInstanceDialog() {
    this.dialog.open(SelectInstanceDialog, {
      width: '650px',
      panelClass: ['no-padding-dialog'],
    });
  }

  toggleSidebar() {
    this.preferenceStore.setValue('sidebar', !this.preferenceStore.getValue('sidebar'));
  }

  logout() {
    this.authService.logout(true);
  }

  ngOnDestroy() {
    if (this.userSubscription) {
      this.userSubscription.unsubscribe();
    }
  }
}
