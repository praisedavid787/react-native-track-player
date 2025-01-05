import { useState, useEffect } from 'react';
import { AppState, AppStateStatus } from 'react-native';

export function useAppIsInBackground() {
  const [state, setState] = useState<AppStateStatus>('active');

  useEffect(() => {
    const onStateChange = (nextState: AppStateStatus) => {
      setState(nextState);
    };

    const listener = AppState.addEventListener('change', onStateChange);

    return () => {
      listener.remove();
    };
  }, []);
  return state === 'background';
}
