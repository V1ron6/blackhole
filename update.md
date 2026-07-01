# Blackhole Browser - Feature Update Plan

## Overview
Comprehensive feature expansion adding mode-based UI, advanced developer tools, and enhanced functionality while maintaining session-based data clearing.

## Key Requirements

### 1. **Icon Design & UI Refresh**
- Replace icons with Lucide icons or similar modern icon set
- Maintain minimal, clean aesthetic
- Ensure proper sizing and contrast

### 2. **Browser Mode System** (Session-Based)
Default: **Basic** (resets to Basic after session clear)

#### **Mode: Basic** (Default)
- Current functionality (2 tabs, URL bar, navigation)
- Add notification/alert system (organized, non-intrusive)
- Request method selection (GET default)

#### **Mode: Intermediate** (Postman-like)
- All Basic features
- Request method selector (GET, POST, PUT, PATCH, DELETE)
- Request header editor
- Body editor for requests
- Separate menu screen (doesn't affect basic flow)
- Dedicated interface with method selection UI

#### **Mode: Advance** (Developer Tools)
- All Intermediate features
- JavaScript console (run arbitrary JS commands)
- Inspector tools (element inspection)
- Network log expansion

#### **Mode: ByteBandit** (Cybersecurity)
- All Advance features
- Advanced settings requiring technical knowledge
- SSH terminal integration
- Advanced proxy configurations
- For cybersecurity professionals only

### 3. **Mode Indicator**
- Display current mode at top of application
- Show with icon/badge
- Allow quick access to mode settings

### 4. **Postman-Like Features** (Intermediate & above)
- **Request Method Selector:** GET, POST, PATCH, PUT, DELETE
- Separate menu screen for method selection
- Request headers editor
- Request body editor (for POST/PUT/PATCH)
- Response viewer integration
- Doesn't interfere with basic browser flow

### 5. **Notification System** (All modes)
- Organized, non-intrusive notifications
- Alert system for important events
- Toast or banner style
- Dismissible
- Queue management

### 6. **JavaScript Console** (Advance mode)
- Execute arbitrary JavaScript in page context
- Command history
- Output formatting
- Error handling
- Separate panel/bottom drawer

### 7. **SSH Terminal Integration** (ByteBandit mode)
- SSH login with username/password
- Terminal-like interface
- Execute remote commands
- Session management
- Security considerations

### 8. **Download Manager**
- Save downloaded files to `blackhole/downloads/` directory
- Files persist for configurable duration (default: 24 hours)
- User-configurable deletion time in Settings
- Download history
- Download management UI

### 9. **Session Persistence & Clearing**
- All mode-specific data is session-scoped
- On session clear: all data goes away
- User returns to Basic mode on app restart
- Download files expire after configured time
- Settings and security preferences persist

### 10. **Settings Menu Updates**
- Mode selection (changes UI per mode)
- Download retention duration (hours, default 24)
- Notification preferences
- All existing security settings

## Implementation Checklist

- [ ] Update icon set to Lucide icons
- [ ] Create BrowserMode enum (BASIC, INTERMEDIATE, ADVANCE, BYTEBANDIT)
- [ ] Add mode indicator UI at top
- [ ] Add mode to Settings.kt
- [ ] Create ModeManager.kt for mode-specific logic
- [ ] Implement RequestMethod selector (Intermediate)
- [ ] Create PostmanRequestDialog for intermediate mode
- [ ] Implement notification system
- [ ] Add JavaScript console (Advance)
- [ ] Add SSH terminal option (ByteBandit)
- [ ] Create DownloadManager.kt
- [ ] Update Settings UI with new options
- [ ] Update MainActivity for mode-aware UI
- [ ] Create separate layouts for each mode
- [ ] Update documentation
- [ ] Update README
- [ ] Build and test APK

## Files to Create/Modify

### New Files
- `ModeManager.kt` - Mode management
- `BrowserMode.kt` - Mode enum and mode-specific configs
- `RequestMethod.kt` - HTTP method enum
- `PostmanRequestDialog.kt` - Postman-like request editor
- `NotificationManager.kt` - Notification system
- `JavaScriptConsole.kt` - JS console implementation
- `SSHTerminal.kt` - SSH terminal integration
- `DownloadManager.kt` - Download file management
- `activity_mode_indicator.xml` - Mode indicator layout
- `activity_postman.xml` - Postman request editor layout
- `activity_console.xml` - JS console layout

### Existing Files to Modify
- `MainActivity.kt` - Add mode-aware UI
- `Settings.kt` - Add new settings
- `SettingsActivity.kt` - Add mode/download settings UI
- `activity_main.xml` - Add mode indicator
- `activity_settings.xml` - Add new setting controls
- `README.md` - Document new features
- `DOCUMENTATION.md` - Document implementation

## Session Data Scope
- Mode setting: Persist (survive session clear)
- Download files: Session-scoped (auto-delete after 24h or configured time)
- Console history: Session-scoped (cleared on session end)
- SSH sessions: Session-scoped (cleared on app close)
- Notifications: Session-scoped (cleared on app close)
- Security settings: Persist (survive session clear)
- Proxy settings: Persist (survive session clear)

## Security Considerations
- SSH credentials: Not stored, requested per session
- SSH key management: Consider secure storage
- Download directory: Sandboxed within app
- JavaScript console: Only runs in page context
- Mode access: All modes available to user
‎new icon design - lucide icons or something 
‎
‎!!it should be indicated at the top which mode the browser is running in,
‎with basic as default 
‎
‎browser modes -(browser modes can be changed on the setting menu ;-each menu with its dedicated interface but once the session is reset it goes back to the basic mode )
‎
‎-basic(basic functions already present)
‎
‎-intermediate (postman integration stuff)
‎
‎-Advance(inspector tools, and a js console)
‎
‎-bytebandit mode(contains all settings and stuffs that require alot of technical knowledge and advanced tools only a cyber security personnel would use)
‎
‎*add a few features of postman, like the ability to change the request method from get ,to post,patch put or delete. but it should be a different menu screen it should affect the basic flow, I show be able to switch between the basic mode and intermediate which is this. is should have the basic functionality of postman mobile.-intermediate
‎
‎*add notifications ability and alert stuffs, but nicer and more organized -basic
‎
‎
‎*add a console (js console from which I can run js command) -Advance 
‎
‎*if possible add an ssh login option ,where any individual can login into their personal servers or cooperate servers with their password and user name to perform tasks, it should have a terminal vibe
‎
‎also add a download menu which saves files downloaded to the download directory 
‎under the blackhole folder this downloads don't delete when the session is cleared immediately but rather after a default of 24 hrs which can be increased by the user on the settings tab
‎
‎
‎
‎
‎
‎
‎!!!! remember all these go away the moment this persistent data go away the moment the session is cleared 
‎
‎update readme and compile 
‎
‎
