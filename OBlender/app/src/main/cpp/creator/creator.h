


extern char strHomePath[256];
extern char strConfigPath[256];
extern void* mainBlenderInitial(int argc, const char **argv);
extern void mainBlenderInitial_reinit(void*pContext);
extern int mainBlenderLoop(void*pContext);
extern void initialLib(void*pNativeWindo);
extern void oblSetValue(int values[],int num);
extern void oblSetValueOn(int values[],int num);
extern void oblSetValueOff(int values[],int num);
extern void oblGetCursorPosition(int *x, int *y);
extern bool oblIsTouchDown(void);
extern const char *oblGetActiveToolId(void);
extern const char *oblGetActiveWorkspace(void);
extern int oblGetActiveMode(void);
extern int oblGetActiveBrushRadius(void);
extern float oblGetActiveBrushStrength(void);
extern void oblGetActiveBrushColor(float rgb[3]);
extern float oblGetActiveBrushExtra(int field);
extern void oblSetActiveBrushRadius(int px);
extern void oblSetActiveBrushStrength(float strength);
extern void oblSetActiveBrushColor(float r, float g, float b);
extern void oblSetActiveBrushExtra(int field, float v);
extern void oblSetActiveBrushColor(float r, float g, float b);
extern void oblSetSculptToolRequest(const char *idname);
extern void oblQueueDispatch(const char *command);
extern void inputKey(int p_physical_keycode,
                     int p_unicode, int p_key_label, int p_pressed,
                     int p_echo);