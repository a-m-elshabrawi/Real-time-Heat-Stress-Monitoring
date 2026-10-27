import pandas as pd
import numpy as np
import random
import matplotlib.pyplot as plt
import seaborn as sns

RANDOM_SEED = 42


def scenario_weights_for_date(current_date):
    """Return seasonal daily scenario weights for Kuwait's heat-risk window."""
    current_date = pd.Timestamp(current_date)

    # Scenario IV is reserved for the severe summer window. Its occurrence
    # ramps up through June, peaks around late June/early July, and tapers
    # through August. May and September retain only lower-intensity scenarios.
    if current_date.month == 6:
        scenario_iv_weight = 0.08 if current_date.day < 16 else 0.30
    elif current_date.month == 7:
        scenario_iv_weight = 0.30 if current_date.day <= 15 else 0.20
    elif current_date.month == 8:
        scenario_iv_weight = 0.12
    else:
        scenario_iv_weight = 0.0

    control_weight = 0.10
    standard_weight = (1.0 - control_weight - scenario_iv_weight) / 3.0
    return [control_weight, standard_weight, standard_weight, standard_weight,
            scenario_iv_weight]


def estimate_hi_c(T_C, RH):
    T = T_C * 1.8 + 32.0
    HI = (-42.379 + 2.04901523*T + 10.14333127*RH
           - 0.22475541*T*RH - 0.00683783*T**2 - 0.05481717*RH**2
           + 0.00122874*T**2*RH + 0.00085282*T*RH**2
           - 0.00000199*T**2*RH**2)
    if RH < 13 and 80 <= T <= 112:
        HI -= ((13 - RH) / 4) * np.sqrt((17 - np.abs(T - 95.0)) / 17)
    elif RH > 85 and 80 <= T <= 87:
        HI += ((RH - 85) / 10) * ((87 - T) / 5)
    return (HI - 32) * 5.0 / 9.0

def get_deterministic_baseline_hr_at_t(t):
    hr_sleep, hr_day = 65.0, 80.0
    if t.hour < 7 or t.hour >= 23:
        return hr_sleep
    elif t.hour == 7:
        frac = (t.minute + t.second / 60) / 60.0
        return hr_sleep + frac * (hr_day - hr_sleep)
    elif t.hour == 22:
        frac = (t.minute + t.second / 60) / 60.0
        return hr_day - frac * (hr_day - hr_sleep)
    else:
        return hr_day

def generate_synthetic_data():
    random.seed(RANDOM_SEED)
    np.random.seed(RANDOM_SEED)

    input_file = "data/(v5.1)OKBK_may_to_sep.csv"
    inherited_file = "data/(v6.2)synthetic_scenarios_dataset_aug_to_sep.csv"
    output_file = "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv"
    
    print(f"Loading {input_file}...")
    df_okbk = pd.read_csv(input_file)
    df_okbk['time'] = pd.to_datetime(df_okbk['time'])
    
    # Match the full seasonal coverage of the OKBK source dataset.
    df_okbk = df_okbk[df_okbk['time'].dt.month.isin([5, 6, 7, 8, 9])].copy()
    
    if df_okbk.empty:
        print("Warning: No data for May through September. Using all available data.")
        df_okbk = pd.read_csv(input_file)
        df_okbk['time'] = pd.to_datetime(df_okbk['time'])
        
    df_okbk = df_okbk.set_index('time')
    
    # Resample to 10 min intervals and interpolate
    print("Interpolating weather data to 10-minute intervals...")
    # Keep only temp and humidity
    df_weather = df_okbk[['temperatur (C)', 'relative_humidity (%)']].copy()
    # Resample
    df_weather = df_weather.resample('10min').interpolate(method='linear')
    
    
    start_time = pd.to_datetime("2025-05-01 00:00:00")
    end_time = pd.to_datetime("2025-07-31 23:50:00")
    
    timeline = pd.date_range(start=start_time, end=end_time, freq='10min')
    
    df_weather = df_weather.reindex(timeline)
    df_weather = df_weather.ffill().bfill()
    
    data = []
    
    # Group by days
    dates = np.unique(timeline.date)
    
    print("Generating scenarios...")
    for current_date in dates:
        day_times = timeline[timeline.date == current_date]
        
        # Pick a daily scenario using season-aware probabilities.
        scenario_choice = random.choices(
            [0, 1, 2, 3, 4],
            weights=scenario_weights_for_date(current_date),
        )[0]
        
        # Choose a random start time for the scenario between 10:00 and 15:00
        scenario_start_hour = random.randint(10, 15)
        scenario_start_time = pd.to_datetime(f"{current_date} {scenario_start_hour:02d}:00:00")
        
        # Stateful variables for the daily scenario transitions
        hr_last_active = None
        
        for t in day_times:
            # Base weather
            amb_temp = df_weather.loc[t, 'temperatur (C)']
            amb_rh = df_weather.loc[t, 'relative_humidity (%)']
            
            # Default Control values
            hr = random.uniform(70, 90)
            temp = amb_temp
            rh = amb_rh
            activity = "Control - Normal"
            scenario = "Control"

            # ── Sleep / wake transition logic ──────────────────────────────
            # Full sleep:   23:00 – 07:00
            # Wake ramp:    07:00 – 08:00  (sleep → daytime, linear)
            # Evening ramp: 22:00 – 23:00  (daytime → sleep, linear)
            hr_sleep,   hr_day   = 65.0, 80.0
            temp_sleep, temp_day = 24.0, amb_temp
            rh_sleep,   rh_day   = 45.0, amb_rh

            if t.hour < 7 or t.hour >= 23:
                # Deep sleep
                hr   = random.uniform(60, 70)
                temp = temp_sleep
                rh   = rh_sleep
                activity = "Control - Sleep"
                scenario = "Control"

            elif t.hour == 7:
                # Morning wake-up ramp (0 = start of hour, 1 = end)
                frac = (t.minute + t.second / 60) / 60.0
                hr   = random.uniform(
                    hr_sleep  + frac * (hr_day  - hr_sleep)  - 3,
                    hr_sleep  + frac * (hr_day  - hr_sleep)  + 3)
                temp = temp_sleep + frac * (temp_day - temp_sleep)
                rh   = rh_sleep   + frac * (rh_day   - rh_sleep)
                activity = "Control - Wake-up Transition"
                scenario = "Control"

            elif t.hour == 22:
                # Evening wind-down ramp (0 = start of hour, 1 = end)
                frac = (t.minute + t.second / 60) / 60.0
                hr   = random.uniform(
                    hr_day  - frac * (hr_day  - hr_sleep) - 3,
                    hr_day  - frac * (hr_day  - hr_sleep) + 3)
                temp = temp_day  - frac * (temp_day - temp_sleep)
                rh   = rh_day    - frac * (rh_day   - rh_sleep)
                activity = "Control - Wind-down Transition"
                scenario = "Control"
            
            # Scenario logic
            mins_since_start = (t - scenario_start_time).total_seconds() / 60
            
            # 1. Active Scenario Phase
            if 0 <= mins_since_start < 480 and scenario_choice != 0:
                # Determine standard active values
                hr_active = hr
                if scenario_choice == 1:
                    # Scenario I: Exam (240 mins) -> Outdoors (240 mins)
                    if mins_since_start < 240:
                        hr_active = random.uniform(95, 115)
                        temp = 22.0
                        rh = 45.0
                        activity = "Scenario I - Exam Indoors"
                    else:
                        hr_active = random.uniform(115, 135)
                        temp = amb_temp
                        rh = amb_rh
                        activity = "Scenario I - Post-Exam Outdoors"
                    scenario = "Scenario I"
                
                elif scenario_choice == 2:
                    # Scenario II: Walking long distances outdoors (480 mins)
                    progress = mins_since_start / 480.0
                    base_hr = 90 + (50 * progress)
                    hr_active = random.uniform(base_hr - 5, base_hr + 5)
                    temp = amb_temp
                    rh = amb_rh
                    activity = "Scenario II - Long Walk Outdoors"
                    scenario = "Scenario II"
                
                elif scenario_choice == 3:
                    # Scenario III: Exercise indoors (180 mins) -> Outdoors (300 mins)
                    if mins_since_start < 180:
                        hr_active = random.uniform(130, 175)
                        temp = 20.0
                        rh = 45.0
                        activity = "Scenario III - Exercise Indoors"
                    else:
                        progress = (mins_since_start - 180) / 300.0
                        base_hr = 145 - (25 * progress)
                        hr_active = random.uniform(base_hr - 10, base_hr + 10)
                        temp = amb_temp
                        rh = amb_rh
                        activity = "Scenario III - Cool-down Outdoors"
                    scenario = "Scenario III"
                
                elif scenario_choice == 4:
                    # Scenario IV: Extreme Heat Exertion / Heat Stroke (480 mins)
                    hr_active = random.uniform(150, 195)
                    temp = amb_temp
                    rh = amb_rh
                    activity = "Scenario IV - Extreme Outdoor Exertion"
                    scenario = "Scenario IV"

                # Apply 20-minute Onset (Warm-up) linear ramp
                if mins_since_start < 20:
                    hr_base = hr  # This is the sleep/wake/daytime control hr already set above
                    progress = mins_since_start / 20.0
                    hr = hr_base + progress * (hr_active - hr_base)
                else:
                    hr = hr_active
                
                # Record the last active heart rate
                hr_last_active = hr

            # 2. Recovery Phase (180 minutes post-scenario)
            elif 480 <= mins_since_start < 660 and scenario_choice != 0:
                T_rec = mins_since_start - 480
                hi_t = estimate_hi_c(temp, rh)  # temp and rh are already set to sleep or default daytime control
                
                # Recovery decay constant scaled by ambient Heat Index
                k = max(0.01, 0.03 - 0.0007 * (hi_t - 25.0))
                
                # Deterministic baseline HR for this time
                hr_base_det = get_deterministic_baseline_hr_at_t(t)
                
                if hr_last_active is None:
                    if scenario_choice == 1:
                        hr_last_active = 125.0
                    elif scenario_choice == 2:
                        hr_last_active = 140.0
                    elif scenario_choice == 3:
                        hr_last_active = 120.0
                    elif scenario_choice == 4:
                        hr_last_active = 172.5

                # Exponential decay towards baseline
                hr_target = hr_base_det + (hr_last_active - hr_base_det) * np.exp(-k * T_rec)
                hr = hr_target + random.uniform(-2, 2)
                
                # Map scenario metadata
                scenario_names = {1: "Scenario I", 2: "Scenario II", 3: "Scenario III", 4: "Scenario IV"}
                scenario = scenario_names[scenario_choice]
                activity = f"{scenario} - Recovery"
            
            # Compile row
            row = {
                'ts': t.strftime('%m/%d/%Y %H:%M'),
                'hr': round(hr, 1),
                'temp': round(temp, 1),
                'w_hum': round(rh, 1),
                'scenario': scenario,
                'activity': activity,
                'HI': 0,
                'CT': 0,
                'HR_HSRS': 0,
                'HI_HSRS': 0,
                'CT_HSRS': 0,
                'Final_Two_HSRS': 0,
                'Final_Three_HSRS': 0,
                'HI_Rothfusz': 0
            }
            data.append(row)
            
    df_out = pd.DataFrame(data)

    print(f"Appending unchanged August–September rows from {inherited_file}...")
    df_inherited = pd.read_csv(inherited_file)
    df_out = pd.concat([df_out, df_inherited[df_out.columns]], ignore_index=True)
    
    # Save to CSV
    print(f"Saving generated data to {output_file}...")
    # Ensure data directory exists
    import os
    if not os.path.exists('data'):
        os.makedirs('data')
    df_out.to_csv(output_file, index=False)
    print("Done!")
    
    
    mask = (pd.to_datetime(df_out['ts']) >= "2025-05-10") & (pd.to_datetime(df_out['ts']) < "2025-05-13")
    df_plot = df_out[mask].copy()
    df_plot['ts_dt'] = pd.to_datetime(df_plot['ts'])
    
    plt.figure(figsize=(15, 8))
    
    plt.subplot(3, 1, 1)
    plt.plot(df_plot['ts_dt'], df_plot['hr'], label='Heart Rate (bpm)', color='red')
    plt.ylabel('HR (bpm)')
    plt.title('Heart Rate over 3 Days (May 10 - May 12)')
    plt.grid(True)
    
    plt.subplot(3, 1, 2)
    plt.plot(df_plot['ts_dt'], df_plot['temp'], label='Temp (C)', color='orange')
    plt.ylabel('Temp (C)')
    plt.grid(True)
    
    plt.subplot(3, 1, 3)
    plt.plot(df_plot['ts_dt'], df_plot['w_hum'], label='Humidity (%)', color='blue')
    plt.ylabel('RH (%)')
    plt.xlabel('Time')
    plt.grid(True)
    
    plt.tight_layout()
    plot_file = "synthetic_data_plot_v6_3.png"
    plt.savefig(plot_file, dpi=150)
    print(f"Saved plot to {plot_file}")

if __name__ == "__main__":
    generate_synthetic_data()
